# Running the server

=== "Nx (bun)"

    The simplest option if you have bun installed. This starts the FHIR server and the frontend dev server together:

    ```bash
    bun install   # first time only
    bun serve
    ```

    The FHIR server will be at `http://localhost:8080/fhir` and the frontend at `http://localhost:3000`

=== "Maven + Bun"

    Run the server directly without bun or Nx:

    ```bash
    cd server
    mvn spring-boot:run
    ```

    The FHIR server will be available at `http://localhost:8080/fhir`

    The frontend can be run in a separate terminal with:

    ```bash
    cd frontend
    bun install   # first time only
    bun dev
    ```

    The frontend will be available at `http://localhost:3000`

=== "Docker"

    Build and run everything in one image:

    ```bash
    docker build -t br-payer .
    docker run -p 8080:8080 br-payer
    ```

    The frontend is bundled into the server and available at `http://localhost:8080` with the FHIR endpoint at `http://localhost:8080/fhir`

## What happens at startup

When the server starts, it does several things before it's ready to accept requests:

1. **Downloads IG packages.** The CRD, DTR, and PAS implementation guide packages are fetched from `build.fhir.org` and installed into the database. This only happens the first time the server starts (or after the H2 in-memory database is cleared on restart).

2. **Loads initial data.** FHIR resources from the `initial-data` directories (`examples-crd`, `examples-dtr`, `examples-pas`, `seed-data`, `library`) are upserted into the database. The loader retries dependency ordering automatically. If a resource has a reference to another resource that hasn't loaded yet, it will retry that resource until all are loaded or it hits a retry limit. Results of any failure will be shown in the logs.

3. **Compiles CQL.** Library resources that reference `.cql` files via `content.url` get their CQL source resolved from disk and compiled to ELM (the executable format). Both of these will be added to the Library resource and will be available at the `/fhir/Library` endpoint.

!!! note
    The database is H2 in-memory by default. All data is reloaded on every restart. You can change the connection string in the `application.yaml` configuration file if persistent storage is required.
