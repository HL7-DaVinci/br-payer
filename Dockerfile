# Multi-stage Dockerfile for FHIR Server with TanStack SPA Frontend

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
COPY frontend/ ./frontend/

# Build the frontend for production
WORKDIR /app/frontend
RUN bun run build

##########################################################################
# Stage 2: Build the HAPI FHIR Server
##########################################################################
FROM docker.io/library/maven:3.9.11-eclipse-temurin-17 AS build-server

WORKDIR /tmp/hapi-fhir-jpaserver-starter

# Download OpenTelemetry agent
ARG OPENTELEMETRY_JAVA_AGENT_VERSION=2.20.1
RUN curl -LSsO https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OPENTELEMETRY_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar

# Copy Maven configuration and download dependencies
COPY server/pom.xml .
RUN mvn -ntp dependency:go-offline

# Copy server source code
COPY server/src/ /tmp/hapi-fhir-jpaserver-starter/src/

# Copy frontend build artifacts to server's static resources directory
COPY --from=build-frontend /app/frontend/dist/ /tmp/hapi-fhir-jpaserver-starter/src/main/resources/static/

# Build the server
RUN mvn clean install -DskipTests -Djdk.lang.Process.launchMechanism=vfork

##########################################################################
# Stage 3: Package for Spring Boot
##########################################################################
FROM build-server AS build-distroless
RUN mvn package -DskipTests spring-boot:repackage -Pboot
RUN mkdir /app && cp /tmp/hapi-fhir-jpaserver-starter/target/ROOT.war /app/main.war

##########################################################################
# Stage 4: Final Production Image (Distroless)
##########################################################################
FROM gcr.io/distroless/java17-debian12:nonroot AS default

USER 65532:65532
WORKDIR /app

COPY --chown=nonroot:nonroot --from=build-distroless /app /app
COPY --chown=nonroot:nonroot --from=build-server /tmp/hapi-fhir-jpaserver-starter/opentelemetry-javaagent.jar /app

EXPOSE 8080

ENTRYPOINT ["java", "--class-path", "/app/main.war", "-Dloader.path=main.war!/WEB-INF/classes/,main.war!/WEB-INF/,/app/extra-classes", "org.springframework.boot.loader.PropertiesLauncher"]
