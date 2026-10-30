package com.fivexbread.common;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 오류 응답을 RFC 9457 ProblemDetail(application/problem+json)로 통일한다 (정의서 10장).
 * 공통 필드: type, title, status, detail, instance + code, traceId
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String PROBLEM_TYPE_BASE = "https://bakery.example/problems/";

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        ProblemDetail problem = problemOf(errorCode, errorCode.getStatus(), ex.getDetail(), request);
        ex.getProperties().forEach(problem::setProperty);
        return ResponseEntity.status(errorCode.getStatus()).headers(ex.getHeaders()).body(problem);
    }

    // 메서드 보안(@PreAuthorize)이 던지는 예외. 아래 Exception 핸들러에 잡혀 500이 되지 않게 먼저 처리
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        return respond(ErrorCode.FORBIDDEN, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
        return respond(ErrorCode.UNAUTHENTICATED, request);
    }

    // Redis 장애: 예약을 503으로 중단한다 (Q-05). 복구되면 재고 키를 DB 기준으로 다시 채운다
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Object> handleRedisDown(RedisConnectionFailureException ex, WebRequest request) {
        log.error("Redis 연결 실패", ex);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, request);
    }

    // 처리하지 못한 예외: 내부 메시지는 응답에 넣지 않고 traceId로 로그와 연결
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        ProblemDetail problem = problemOf(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getStatus(), null, request);
        log.error("처리하지 못한 예외 traceId={}", problem.getProperties().get("traceId"), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problemOf(ErrorCode.INVALID_REQUEST, status, null, request);
        Stream<FieldViolation> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldViolation::of);
        Stream<FieldViolation> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldViolation(null, error.getCode(), error.getDefaultMessage()));
        problem.setProperty("errors", Stream.concat(fieldErrors, globalErrors).toList());
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    /**
     * Spring MVC 표준 예외(404, 405 + Allow, 413, 본문 파싱 실패 등)도 같은 형식으로 바꾼다.
     * 상태 코드는 Spring이 정한 값을 유지하고, Spring 기본 detail은 내부 정보가 섞일 수 있어 버린다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode errorCode = errorCodeOf(statusCode);
        ProblemDetail problem = problemOf(errorCode, statusCode, null, request);
        if (statusCode.is5xxServerError()) {
            log.error("서버 오류 traceId={}", problem.getProperties().get("traceId"), ex);
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    private ResponseEntity<Object> respond(ErrorCode errorCode, WebRequest request) {
        ProblemDetail problem = problemOf(errorCode, errorCode.getStatus(), null, request);
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    private static ProblemDetail problemOf(
            ErrorCode errorCode, HttpStatusCode status, @Nullable String detail, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_TYPE_BASE + errorCode.code().toLowerCase().replace('_', '-')));
        problem.setTitle(errorCode.getMessage());
        problem.setDetail(detail);
        if (request instanceof ServletWebRequest servletRequest) {
            problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        problem.setProperty("code", errorCode.code());
        problem.setProperty("traceId", traceId());
        return problem;
    }

    private static ErrorCode errorCodeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 412 -> ErrorCode.PRECONDITION_FAILED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 429 -> ErrorCode.TOO_MANY_REQUESTS;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.INVALID_REQUEST;
        };
    }

    // 분산 추적을 붙이면 MDC의 traceId를 그대로 쓰고, 없으면 16자리 hex를 새로 만든다
    private static String traceId() {
        String fromMdc = MDC.get("traceId");
        if (fromMdc != null) {
            return fromMdc;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public record FieldViolation(String field, String code, String message) {

        static FieldViolation of(FieldError error) {
            return new FieldViolation(error.getField(), error.getCode(), error.getDefaultMessage());
        }
    }
}
