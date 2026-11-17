package com.fivexbread.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 명세를 실제 응답에 맞춘다. 프론트가 이 명세로 타입을 만든다 (decisions.md 6장 "OpenAPI 명세").
 * <ul>
 *   <li>응답 스키마의 required: springdoc 은 record 필드를 모두 선택 항목으로 내보내 생성한 타입이 전부 optional 이 된다.
 *       우리 응답은 null 인 필드도 빼지 않고 내보내므로(Jackson 기본), 응답에만 쓰이는 스키마의 모든 필드를 required 로 둔다.
 *       값이 null 일 수 있는 필드는 @Schema(nullable = true) 로 표시한다(3.1 에서는 type 에 "null"). 그러면 생성한 타입이
 *       "항상 있고 null 일 수 있음"(canceledAt: string | null)이 된다. 요청에 쓰이는 스키마는 검증 애너테이션이 정한 대로 둔다</li>
 *   <li>오류: 모든 API 의 default 응답을 RFC 9457 ProblemDetail + code(ErrorCode) 로 (ApiExceptionHandler)</li>
 * </ul>
 * 실제 응답이 이 명세와 맞는지는 OpenApiContractTest 가 본다.
 */
@Configuration
public class OpenApiConfig {

    static final String PROBLEM = "Problem";
    static final String PROBLEM_JSON = "application/problem+json";

    @Bean
    OpenApiCustomizer responseContract() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            schemas.put(PROBLEM, problemSchema());
            Set<String> responseSide = new HashSet<>();
            Set<String> requestSide = new HashSet<>();
            operations(openApi).forEach(operation -> {
                if (operation.getRequestBody() != null) {
                    collect(operation.getRequestBody().getContent(), schemas, requestSide);
                }
                if (operation.getParameters() != null) {
                    operation.getParameters().stream().map(Parameter::getSchema).filter(Objects::nonNull)
                            .forEach(schema -> collect(schema, schemas, requestSide));
                }
                operation.getResponses().values().forEach(response -> collect(response.getContent(), schemas, responseSide));
                operation.getResponses().putIfAbsent("default", new ApiResponse()
                        .description("오류 (RFC 9457 ProblemDetail + code)")
                        .content(new Content().addMediaType(PROBLEM_JSON,
                                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM)))));
            });
            responseSide.removeAll(requestSide);
            responseSide.remove(PROBLEM);
            responseSide.forEach(name -> requireAll(schemas.get(name)));
            schemas.values().forEach(OpenApiConfig::allowNullInEnums);
        };
    }

    private static List<Operation> operations(OpenAPI openApi) {
        return openApi.getPaths().values().stream().flatMap(path -> path.readOperations().stream()).toList();
    }

    private static void requireAll(Schema<?> schema) {
        if (schema != null && schema.getProperties() != null) {
            schema.setRequired(List.copyOf(schema.getProperties().keySet()));
        }
    }

    /** OpenAPI 3.1 은 nullable 대신 type 에 "null" 을 넣는다(@Schema(nullable = true) 가 그렇게 나온다) */
    static boolean nullable(Schema<?> schema) {
        return Boolean.TRUE.equals(schema.getNullable()) || (schema.getTypes() != null && schema.getTypes().contains("null"));
    }

    // null 이 될 수 있는 enum 필드(예: 이력의 fromStatus)는 enum 목록에도 null 이 있어야 JSON Schema 로 맞다
    @SuppressWarnings("unchecked")
    private static void allowNullInEnums(Schema<?> schema) {
        if (schema.getProperties() == null) {
            return;
        }
        schema.getProperties().values().forEach(value -> {
            Schema<Object> property = (Schema<Object>) value;
            if (nullable(property) && property.getEnum() != null && !property.getEnum().contains(null)) {
                property.getEnum().add(null);
            }
        });
    }

    private static void collect(Content content, Map<String, Schema> schemas, Set<String> names) {
        if (content != null) {
            content.values().stream().map(MediaType::getSchema).filter(Objects::nonNull)
                    .forEach(schema -> collect(schema, schemas, names));
        }
    }

    // $ref 를 따라가며 닿는 컴포넌트 이름을 모은다
    @SuppressWarnings("unchecked")
    private static void collect(Schema<?> root, Map<String, Schema> schemas, Set<String> names) {
        Deque<Schema<?>> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            Schema<?> schema = queue.pop();
            if (schema.get$ref() != null) {
                String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
                if (names.add(name) && schemas.get(name) != null) {
                    queue.push(schemas.get(name));
                }
                continue;
            }
            Consumer<Schema<?>> push = child -> {
                if (child != null) {
                    queue.push(child);
                }
            };
            if (schema.getProperties() != null) {
                schema.getProperties().values().forEach(child -> push.accept((Schema<?>) child));
            }
            push.accept(schema.getItems());
            if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
                push.accept(additional);
            }
            if (schema.getAllOf() != null) {
                schema.getAllOf().forEach(child -> push.accept((Schema<?>) child));
            }
        }
    }

    /** ApiExceptionHandler 가 만드는 본문. 업무 예외에 따라 속성이 더 붙는다(rejoinAvailableAt, currentStatus 등) */
    private static Schema<?> problemSchema() {
        List<String> codes = Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList();
        Schema<?> problem = new ObjectSchema()
                .description("RFC 9457 ProblemDetail. code 로 분기한다. 업무 예외에 따라 다른 속성이 더 붙을 수 있다")
                .addProperty("type", new StringSchema().format("uri"))
                .addProperty("title", new StringSchema())
                .addProperty("status", new IntegerSchema())
                .addProperty("detail", new Schema<>().types(new LinkedHashSet<>(List.of("string", "null"))))
                .addProperty("instance", new StringSchema().format("uri"))
                .addProperty("code", new StringSchema()._enum(codes))
                .addProperty("traceId", new StringSchema())
                .addProperty("errors", new ArraySchema().items(new ObjectSchema().additionalProperties(true))
                        .description("위반 항목 전부. 모양은 code 마다 다르다: INVALID_REQUEST {field, code, message}, "
                                + "OUT_OF_STOCK·PER_USER_LIMIT_EXCEEDED {breadId, reason, remaining}, BREAD_NOT_AVAILABLE {breadId}, "
                                + "INVALID_STOCK_ROWS {breadId, field, reason, reserved}, INVALID_SALE_DATE {field, reason, message}"));
        problem.setRequired(List.of("type", "title", "status", "instance", "code", "traceId"));
        problem.setAdditionalProperties(true);
        return problem;
    }
}
