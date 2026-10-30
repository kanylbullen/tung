# syntax=docker/dockerfile:1
#
# tung-server: one image, built from the repository root:
#   docker build -f server/Dockerfile -t tung-server .
# See deploy/ for a compose file with Postgres.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Only what the server build needs (see Dockerfile.dockerignore): the Gradle
# wrapper and root build files, core/, server/, web/ (the web app the server
# serves at /app/), the catalogue, and the app's typeface and its licence.
COPY . .
# The image has no Android SDK, so build without the Android modules: keep only
# the include lines for :core, :server and :web.
RUN sed -i -E '/^include\(":(core|server|web)"\)/!{/^include\(/d}' settings.gradle.kts \
 && grep '^include' settings.gradle.kts
# The web app is Kotlin/JS: Gradle downloads Node.js and Yarn (cached with the rest).
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:installDist :web:jsBrowserDistribution --no-daemon --console=plain

FROM eclipse-temurin:21-jre
RUN groupadd --system tung && useradd --system --gid tung --home-dir /app --shell /usr/sbin/nologin tung
COPY --from=build /src/server/build/install/tung-server /app
COPY --from=build /src/web/build/dist/js/productionExecutable /app/web
USER tung
WORKDIR /app
ENV PORT=8080 \
    TUNG_WEB_DIR=/app/web \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/healthz" || exit 1
ENTRYPOINT ["/app/bin/tung-server"]
