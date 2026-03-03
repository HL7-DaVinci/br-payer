# DaVinci Burden Reduction Payer Server

[![Build Status](https://ci.hl7.org/api/badges/HL7-DaVinci/br-payer/status.svg)](https://ci.hl7.org/HL7-DaVinci/br-payer)

This is a reference implementation FHIR server built on the [HAPI FHIR JPA Starter Server](https://github.com/hapifhir/hapi-fhir-jpaserver-starter) in an [Nx](https://nx.dev) workspace.

It implements the following Da Vinci implementation guides:
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

## VSAC terminology

Some CQL rules reference ValueSets hosted by the [VSAC](https://vsac.nlm.nih.gov/) (Value Set Authority Center). To expand these at runtime, set the `VSAC_API_KEY` environment variable:

```bash
export VSAC_API_KEY=your-api-key-here
```

To get an API key, register for a free UMLS account at <https://uts.nlm.nih.gov/uts/>, then generate a key from your profile page.

## Documentation

Full documentation is available in the `docs/` directory and can be served locally with:

```bash
mkdocs serve
```

The docs cover [configuration](docs/configuration.md), [architecture](docs/architecture.md), [clinical content authoring](docs/clinical-content/library-modules.md), and [test scenario generation](docs/clinical-content/scenarios.md).
