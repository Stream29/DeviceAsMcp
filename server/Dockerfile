# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S deviceasmcp && \
    adduser -S -D -H -G deviceasmcp -h /app deviceasmcp
WORKDIR /app
COPY --from=build --chown=deviceasmcp:deviceasmcp /workspace/server/build/install/server/ ./
USER deviceasmcp
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD ["wget", "-q", "-O", "/dev/null", "http://127.0.0.1:8080/health/ready"]
ENTRYPOINT ["./bin/server"]
