# Prerequisites

## Server

Running the server requires:

- **Java 17** or newer. The server compiles and runs on Java 17. Later LTS releases (21, etc.) also work.
- **Maven 3.9+**. Used to build the server, run tests, and compile CQL to ELM.

## Frontend

Running the frontend dev configuration requires:

- **bun 1+** (or Node 22+) 

## Docker 

For containerized builds and deployment. The multi-stage Dockerfile handles the frontend and server builds internally, so you do not need bun or Maven installed when building with Docker.
