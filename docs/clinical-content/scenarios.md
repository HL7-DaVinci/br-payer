# Scenarios and testing

The server generates test requests automatically from the library modules. No hand-written JSON fixtures required. The scenario system reads PlanDefinition metadata and builds valid requests that correspond to the use cases for each IG.

## How discovery works

`LibraryScenarioScanner` scans the FHIR database at runtime for PlanDefinition and Questionnaire resources. For each PlanDefinition it finds, it extracts the focus codes, hook triggers, order types, and linked questionnaire URLs, then passes that metadata to IG-specific scenario builders.

Because the scanner reads from the database (not the filesystem), scenarios reflect whatever is currently loaded.

## What gets generated

Each IG produces different request variants from the same PlanDefinition metadata:

**CRD**: one variant per hook trigger on the PlanDefinition. If a PlanDefinition has `order-sign` and `order-select` triggers, you get one scenario for each. The request contains a synthetic patient, coverage, and an order resource matching the focus code.

**DTR**: three variants per Questionnaire: a canonical request (by questionnaire URL), an order-based request (by coverage and order code), and a combined request (both). These map to the different ways a DTR client can invoke `$questionnaire-package`.

**PAS**: five variants per PlanDefinition: initial submission, renewal, update, cancel, and inquiry. Each builds a valid PAS bundle with the correct Claim structure for that submission type.

## Scenario API endpoints

| Endpoint | Description |
|----------|-------------|
| `GET /api/crd/scenarios` | List all CRD test scenarios |
| `GET /api/crd/scenarios/{id}/hooks/{hookName}` | CDS Hooks request JSON for a specific hook |
| `GET /api/dtr/scenarios` | List all DTR test scenarios |
| `GET /api/dtr/scenarios/{id}/requests/{type}` | FHIR Parameters JSON for a DTR variant |
| `GET /api/pas/scenarios` | List all PAS test scenarios |
| `GET /api/pas/scenarios/{id}/variants/{variantId}` | FHIR Bundle JSON for a PAS variant |

The scenario ID is the kebab-case form of the PlanDefinition `name` (e.g., `HomeOxygenTherapy` becomes `home-oxygen-therapy`).

## Testing walkthrough

Here is the full cycle for verifying a new module:

### 1. Add the module

Place the files in `library/YourModule/` (PlanDefinition, Library, CQL, and optionally Questionnaire and ValueSets).

### 2. Restart the server

The data initializer loads new resources on startup. Watch the logs for CQL compilation output. Errors here mean the CQL has syntax issues or unresolved references.

### 3. Check scenario generation

```bash
curl http://localhost:8080/api/crd/scenarios
```

Find the module's scenario in the list. The ID will be the kebab-case version of the PlanDefinition's `name`.

### 4. Test CRD

```bash
# Grab the hook request
curl http://localhost:8080/api/crd/scenarios/{module-id}/hooks/order-sign -o hook-request.json

# Send it
curl -X POST http://localhost:8080/cds-services/order-sign-crd \
  -H "Content-Type: application/json" \
  -d @hook-request.json
```

Inspect the response cards. If the `"Rule Applies"` expression evaluated to true, the response should include a card with the module's summary and detail text.

### 5. Test DTR

```bash
curl http://localhost:8080/api/dtr/scenarios/{module-id}/requests/canonical -o dtr-request.json

curl -X POST http://localhost:8080/fhir/Questionnaire/\$questionnaire-package \
  -H "Content-Type: application/fhir+json" \
  -d @dtr-request.json
```

The response should contain a Bundle with the Questionnaire, its Library, and any referenced ValueSets.

### 6. Test PAS

```bash
curl http://localhost:8080/api/pas/scenarios/{module-id}/variants/initial -o pas-request.json

curl -X POST http://localhost:8080/fhir/Claim/\$submit \
  -H "Content-Type: application/fhir+json" \
  -d @pas-request.json
```

The response is a ClaimResponse bundle. Check the review action code: A1 (certified), A3 (not certified), or A4 (pended) depending on what the CQL evaluates.

## Build-time fixture generation

`TestRequestFileGenerator` runs during Maven's `process-classes` phase and writes generated request files to `target/test-requests/`:

```
target/test-requests/
  crd/
    order-sign/
      home-oxygen-therapy-order-sign.json
    order-select/
      ...
  dtr/
    home-oxygen-therapy-canonical-request.json
    home-oxygen-therapy-order-request.json
  pas/
    ...
```

These files are useful for offline testing or importing into API tools like Postman. They are regenerated on every build.
