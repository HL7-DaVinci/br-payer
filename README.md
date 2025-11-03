# DaVinci Burden Reduction Payer Server

This is a reference implementation FHIR server built on the [HAPI FHIR JPA Starter Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) in an [Nx](https://nx.dev) workspace.

It is designed to support the following implementation guides:
- [Coverage Requirements Discovery (CRD)](https://build.fhir.org/ig/HL7/davinci-crd/)
- [Documentation Templates and Rules (DTR)](https://build.fhir.org/ig/HL7/davinci-dtr/)
- [Prior Authorization Support (PAS)](https://build.fhir.org/ig/HL7/davinci-pas/)

## Prerequisites

- Required to run the server
  - Java 17+
  - Maven
- Optional
  - Node.js (if using Nx commands)
  - [Bun](https://bun.sh/) can also be used as a substitute for the `npm` and `npx` commands
  - Docker (optional)

## Quick Start

### Option 1: Run with Nx

The easiest way to run the server in development:

```bash
# Install dependencies
npm ci

# Start the FHIR server
npx nx serve server
```

The server will be available at `http://localhost:8080/fhir`

### Option 2: Run with Maven Spring Boot

Navigate to the server directory and use Maven directly:

```bash
cd server
mvn spring-boot:run
```

### Option 3: Run with Docker

Build and run the server using Docker:

```bash
# Build the Docker image (distroless variant - production)
docker build -t br-payer .

# Run the container
docker run -p 8080:8080 br-payer
```

## Configuration

The server configuration can be customized in `server/src/main/resources/application.yaml`:

## API Endpoints

Once running, the FHIR server exposes:

- **FHIR Base URL**: `http://localhost:8080/fhir`
- **Metadata**: `http://localhost:8080/fhir/metadata`
- **Health Check**: `http://localhost:8080/actuator/health`
