# DaVinci Burden Reduction Payer Server

[![Build Status](https://ci.hl7.org/api/badges/HL7-DaVinci/br-payer/status.svg)](https://ci.hl7.org/HL7-DaVinci/br-payer)

This is a reference implementation FHIR server built on the [HAPI FHIR JPA Starter Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) in an [Nx](https://nx.dev) workspace.

It is designed to support the following implementation guides:
- [Coverage Requirements Discovery (CRD)](https://build.fhir.org/ig/HL7/davinci-crd/)
- [Documentation Templates and Rules (DTR)](https://build.fhir.org/ig/HL7/davinci-dtr/)
- [Prior Authorization Support (PAS)](https://build.fhir.org/ig/HL7/davinci-pas/)

## Prerequisites

- Required to run the server
  - Java 17+
  - Maven
- Required to run the frontend
  - Bun 1+ (generally tested with latest) or Node 22+
- Optional
  - Docker

## Quick Start

### Option 1: Run with Nx

The easiest way to run everything in development mode:

```bash
# Install dependencies
bun install

# Start the FHIR server and frontend concurrently
bun serve
```

The server will be available at `http://localhost:8080/fhir` and the frontend at `http://localhost:3000`

### Option 2: Run Separately


Navigate to the server directory and use Maven directly:

```bash
cd server
mvn spring-boot:run
```

### Option 3: Run with Docker

Build and run the server and frontend using Docker:

```bash
# Build the Docker image (this packages the frontend and server together)
docker build -t br-payer .

# Run the container
docker run -p 8080:8080 br-payer
```

The frontend will be available at `http://localhost:8080` with the FHIR endpoint at `http://localhost:8080/fhir`

## Configuration

The server configuration can be customized in `server/src/main/resources/application.yaml`:

## API Endpoints

Once running, the FHIR server exposes:

- **FHIR Base URL**: `http://localhost:8080/fhir`
- **Metadata**: `http://localhost:8080/fhir/metadata`
- **Health Check**: `http://localhost:8080/actuator/health`
