# syntax=docker/dockerfile:1
# 앱 이미지 (정의서 8장 "빌드: Gradle + Dockerfile(멀티 스테이지)"). compose.full.yaml 의 app1~3 이 같은 이미지를 쓴다

# 1) 빌드: 프로젝트의 Gradle Wrapper 로 bootJar. 테스트는 CI·로컬 ./gradlew build 에서 돌리므로 여기서는 뺀다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --launcher --destination extracted

# 2) 실행: JRE 만. 의존성 → 앱 코드 순으로 레이어를 나눠 코드만 바뀌면 마지막 레이어만 다시 받는다
FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --no-create-home app
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER app
ENV TZ=Asia/Seoul
# 시간대: main() 의 TimeZone.setDefault 와 별개로 JVM 시작 옵션에서도 KST 를 보장한다 (decisions.md 3장)
# 메모리: 컨테이너 한도(mem_limit)의 75%를 힙으로. JAVA_OPTS 로 덧붙일 수 있다
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul -XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
