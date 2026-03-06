# Multi-stage Dockerfile for FHIR Server with TanStack SPA Frontend and Documentation

##########################################################################
# Stage 1: Build the TanStack SPA frontend
##########################################################################
FROM oven/bun:1-slim AS build-frontend

WORKDIR /app

# Copy package files and lockfile
COPY frontend/package.json ./frontend/
COPY frontend/bun.lock ./frontend/

# Install dependencies
WORKDIR /app/frontend
RUN bun install --frozen-lockfile

# Copy frontend source code
WORKDIR /app
COPY frontend/index.html ./frontend/
COPY frontend/tsconfig.json ./frontend/
COPY frontend/vite.config.ts ./frontend/
COPY frontend/public/ ./frontend/public/
COPY frontend/src/ ./frontend/src/

# Build the frontend for production
WORKDIR /app/frontend
RUN bun run build

##########################################################################
# Stage 2: Build the documentation site (mkdocs)
##########################################################################
FROM python:3-alpine AS build-docs

WORKDIR /app
COPY mkdocs.yml .
COPY docs/ ./docs/
RUN pip install --no-cache-dir mkdocs-material && mkdocs build

##########################################################################
# Stage 3: Build the HAPI FHIR Server
##########################################################################
FROM docker.io/library/maven:3.9.12-eclipse-temurin-17 AS build-server

WORKDIR /tmp/hapi-fhir-jpaserver-starter

# Download OpenTelemetry agent
ARG OPENTELEMETRY_JAVA_AGENT_VERSION=2.24.0
RUN curl -LSsO https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OPENTELEMETRY_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar

# Copy Maven configuration and download dependencies
COPY server/pom.xml .
RUN mvn -ntp dependency:go-offline

# Copy library directory for FHIR resources (referenced by pom.xml as ../library)
COPY library /tmp/library/

# Copy server source code
COPY server/src/ /tmp/hapi-fhir-jpaserver-starter/src/

# Copy frontend build artifacts to server's static resources directory
COPY --from=build-frontend /app/frontend/dist/ /tmp/hapi-fhir-jpaserver-starter/src/main/resources/static/

# Copy documentation site to static/docs/
COPY --from=build-docs /app/site/ /tmp/hapi-fhir-jpaserver-starter/src/main/resources/static/docs/

# Build the server
RUN mvn clean package -DskipTests -Djdk.lang.Process.launchMechanism=vfork
RUN mkdir /app && cp /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /app/main.war

##########################################################################
# Stage 4: Final Production Image
##########################################################################
FROM gcr.io/distroless/java21-debian13:nonroot AS default
# 65532 is the nonroot user's uid
# used here instead of the name to allow Kubernetes to easily detect that the container
# is running as a non-root (uid != 0) user.
USER 65532:65532
WORKDIR /app

COPY --chown=nonroot:nonroot --from=build-server /app /app
COPY --chown=nonroot:nonroot --from=build-server /tmp/hapi-fhir-jpaserver-starter/opentelemetry-javaagent.jar /app

ENTRYPOINT ["java", "--class-path", "/app/main.war", "-Dloader.path=main.war!/WEB-INF/classes/,main.war!/WEB-INF/,/app/extra-classes", "org.springframework.boot.loader.PropertiesLauncher"]
