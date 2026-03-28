# Configuration

This page covers only the custom properties added by the burden reduction implementation. For standard HAPI FHIR and Spring Boot configuration, see the [HAPI FHIR JPA Starter documentation](https://github.com/hapifhir/hapi-fhir-jpaserver-starter).

All configuration lives in `server/src/main/resources/application.yaml`. Values can be overridden with environment variables using Spring Boot's relaxed binding (e.g., `PAS_PENDED_RESOLUTION_DELAY_SECONDS` for `pas.pended-resolution-delay-seconds`).

## Implementation guides

The server downloads and installs three Da Vinci IG packages at startup:

```yaml
hapi:
  fhir:
    implementationguides:
      hl7fhirusdavincicrd:
        name: hl7.fhir.us.davinci-crd
        version: 2.2.1
        installMode: STORE_AND_INSTALL
      hl7fhirusdavincidtr:
        name: hl7.fhir.us.davinci-dtr
        version: 2.2.0
        installMode: STORE_AND_INSTALL
      hl7fhirusdavincipas:
        name: hl7.fhir.us.davinci-pas
        version: 2.2.1
        installMode: STORE_AND_INSTALL
```

`STORE_AND_INSTALL` means the server stores the package contents in the database and registers the StructureDefinitions, CodeSystems, and ValueSets for validation and CQL evaluation. The `packageUrl` values point to the CI build packages. Replace these with local paths or a different registry for air-gapped environments.

## Initial data directories

```yaml
initial-data:
  - examples-crd
  - examples-dtr
  - examples-pas
  - seed-data
  - library
```

Each entry is a directory under `server/src/main/resources/` (or on the classpath). At startup, the `DataInitializer` loads all `.json` files from these directories into the database. The `library` entry is copied from the `library/` directory at the repository root during the Maven build.

| Directory | Contents |
|-----------|----------|
| `examples-crd` | Sample Patient, Coverage, and order resources for CRD testing |
| `examples-dtr` | Sample resources for DTR testing |
| `examples-pas` | Sample Claim bundles for PAS testing |
| `seed-data` | Organization, Practitioner, and other foundational resources |
| `library` | Clinical rule modules (PlanDefinitions, Libraries, CQL, Questionnaires, ValueSets) |

## CDS Hooks

```yaml
hapi:
  fhir:
    cdshooks:
      enabled: true
```

When enabled, the server registers CDS Hook endpoints under `/cds-services/` (note: this is not under `/fhir`). The hook services are auto-discovered via Spring component scanning. Disabling this turns off all CRD functionality.

## PAS settings

```yaml
pas:
  pended-resolution-delay-seconds: 15
  authorization-number-prefix: "AUTH-"
```

| Property | Default | Description |
|----------|---------|-------------|
| `pas.pended-resolution-delay-seconds` | `15` | Seconds before a pended (A4) item auto-resolves to approved. The `PasPendedResolutionService` schedules a one-time task for each pended ClaimResponse item. |
| `pas.authorization-number-prefix` | `AUTH-` | Prefix for generated authorization numbers (e.g., `AUTH-1234`). |

## DTR settings

```yaml
dtr:
  adaptive:
    # next-question-url: "https://payer.example/fhir/Questionnaire/$next-question"
```

| Property | Default | Description |
|----------|---------|-------------|
| `dtr.adaptive.next-question-url` | `{server_address}/Questionnaire/$next-question` | URL for the adaptive questionnaire `$next-question` endpoint. Override to point at a different server. |

## VSAC terminology

Some CQL rules reference ValueSets by canonical URL rather than bundling the codes inline. To expand these ValueSets at runtime, the server can call the [VSAC](https://vsac.nlm.nih.gov/) (Value Set Authority Center) terminology service from the National Library of Medicine.

### When it is needed

If the CQL includes a `valueset` declaration that points to a VSAC URL (e.g., `http://cts.nlm.nih.gov/fhir/ValueSet/...`) and the corresponding codes are not bundled as a `ValueSet-*.json` file in the module, the server needs VSAC access to expand it.

If all ValueSets are bundled inline in the library modules, VSAC is not required.

### Setup

1. **Register for a UMLS account** at <https://uts.nlm.nih.gov/uts/>. UMLS is free for US-based users; international users may need to complete a license agreement.

2. **Generate an API key** from the UMLS profile page after registration. The key is a long hex string.

3. **Set the environment variable:**

    ```bash
    export VSAC_API_KEY=your-api-key-here
    ```

    Or pass it to Docker:

    ```bash
    docker run -p 8081:8081 -e VSAC_API_KEY=your-api-key-here br-payer
    ```

### Configuration

```yaml
vsac:
  api-key: ${VSAC_API_KEY:}
  # url: https://cts.nlm.nih.gov/fhir  (default)
  warmup:
    enabled: true
```

| Property | Default | Description |
|----------|---------|-------------|
| `vsac.api-key` | (empty) | UMLS API key for VSAC authentication. Set via `VSAC_API_KEY` environment variable. When empty, VSAC features are disabled. |
| `vsac.url` | `https://cts.nlm.nih.gov/fhir` | VSAC FHIR terminology server endpoint. |
| `vsac.warmup.enabled` | `true` | Pre-resolves and expands VSAC-hosted ValueSets referenced by Library dataRequirements at startup. Speeds up the first `$questionnaire-package` call at the cost of longer startup time. Only active when `vsac.api-key` is also set. |

The VSAC terminology client bean activates conditionally: it only starts if the `VSAC_API_KEY` variable is present. Without it, the server runs fine but cannot expand external ValueSets.
