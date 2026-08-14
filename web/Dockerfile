# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS build
RUN apt-get update && \
    apt-get install --no-install-recommends -y libatomic1 && \
    rm -rf /var/lib/apt/lists/*
ENV YARN_CACHE_FOLDER=/root/.cache/yarn
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    --mount=type=cache,target=/root/.cache/yarn,sharing=locked \
    ./gradlew --no-daemon --no-parallel --max-workers=1 :web:wasmJsBrowserDistribution

FROM caddy:2-alpine
COPY web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/web/build/dist/wasmJs/productionExecutable/ /srv/
EXPOSE 80 443
