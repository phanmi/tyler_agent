# Backend image: the Spring Boot JAR plus the PyInstaller worker that Java supervises.
# Build from the repository root. See docs/CLOUD_DEPLOYMENT.md.

FROM maven:3.10-eclipse-temurin-26-noble AS backend-build
WORKDIR /src
COPY .mvn .mvn
COPY pom.xml .
COPY src src
# Maven packages the protocol schema from here.
COPY contracts contracts
# The tests run in the CI "Build and test" job.
RUN mvn --batch-mode -DskipTests package

# Same Ubuntu release as the runtime stage, so the bundle runs on the glibc it was built with.
FROM eclipse-temurin:26-jre-noble AS worker-build
COPY --from=astral/uv:0.13.1 /uv /usr/local/bin/uv
# PyInstaller needs objdump to collect shared libraries on Linux.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends binutils \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# worker.spec bundles the protocol schema and reads the release version from .mvn.
COPY .mvn .mvn
COPY contracts contracts
COPY agent-python agent-python
WORKDIR /src/agent-python
RUN uv sync --locked --group dev \
    && uv run --locked pyinstaller --noconfirm --distpath dist --workpath build worker.spec

FROM eclipse-temurin:26-jre-noble
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    AGENT_WORKSPACE_DIR=/data/workspace \
    LOG_DIR=/data/logs
RUN groupadd --gid 10001 tyler \
    && useradd --uid 10001 --gid tyler --home-dir /data --no-create-home --shell /usr/sbin/nologin tyler \
    && install --directory --owner=tyler --group=tyler --mode=700 /data
WORKDIR /app
COPY --from=backend-build /src/target/tyler-agent-*.jar tyler-agent.jar
# The backend's default worker command resolves this path from the working directory.
COPY --from=worker-build /src/agent-python/dist/tyler-agent-worker agent-python/dist/tyler-agent-worker
USER tyler
VOLUME /data
EXPOSE 8080
# Leave the rest of the container's memory to the Python worker.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-jar", "tyler-agent.jar"]
