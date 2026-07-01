# Workspace Overview

This is an Nx workspace designed to host a FHIR application stack. The stack provides a SPA frontend and a HAPI FHIR Java server backend. The use cases handled include implementing the Da Vinci Burden Reduction implementation guides, with a focus on payer-side functionality.

## Key Constraints

1. **Do NOT modify HAPI starter code** in `src/main/java/ca/uhn/fhir/` - place custom code in `org.hl7.davinci`
2. **Payer-only scope** - This server implements payer operations; DTR app launch URLs are provider-side concerns
3. **IG conformance** - Always refer to the Da Vinci IGs for research, decision making, and correct request/response structures. Do not suggest implementations that deviate from the IG specifications.
4. **CodeSystem for card types** - Use `CrdConstants.CARD_TYPE_SYSTEM`, not hardcoded URLs
5. **Coverage extension** - Always include required elements: `coverage`, `covered`, `date`, `coverage-assertion-id`
6. **IG constants** - Place new FHIR URL constants in the correct IG constants class (see table above), not inline in consuming classes
7. **PAS reuses CRD PlanDefinitions** - Coverage logic is shared between CRD and PAS via `PasCoverageEvaluator`
8. **PAS submission type detection** - Structural (presence of `Claim.related`), not profile-based
9. **H2 in-memory database** - Data is transient and reloaded each startup

## Build & Run Commands

This is an Nx workspace. Prefer running tasks through `nx` instead of underlying tooling.

```bash
# Install dependencies
bun install

# Start both server and frontend
bun serve

# Build all projects
bun run build

# Run all tests
bun run test
```

### Server (Java/Maven)

```bash
# Run server directly with Maven
cd server && mvn spring-boot:run

# Run a single test class
cd server && mvn test -Dtest=OrderSelectServiceTest

# Run a single test method
cd server && mvn test -Dtest=OrderSelectServiceTest#testMethodName

# Build server WAR
cd server && mvn clean package -DskipTests
```

### Frontend (Vite/React)

```bash
# Run frontend dev server (port 3000)
cd frontend && bun dev

# Run frontend tests
cd frontend && bun test

# Lint/format
cd frontend && bun check    # biome check
cd frontend && bun lint     # biome lint
cd frontend && bun format   # biome format

# Build and copy to server static resources
nx run frontend:copy-to-server
```

## Projects

### 1. Server (`server`)
- **Path**: `server/`
- **Type**: Java / Maven / HAPI FHIR
- **Description**: The backend FHIR server.
- **Structure**:
  - `src/main/java/ca/uhn/fhir/` - HAPI starter code (do NOT modify)
  - `src/main/java/org/hl7/davinci/` - Custom implementation code (all custom code goes here)
    - `api/` - REST controllers for non-FHIR APIs (`/api/crd/`, `/api/dtr/`, `/api/pas/`)
    - `cdshooks/services/` - CDS Hook implementations: `OrderSelectService`, `OrderSignService`, `OrderDispatchService`, `AppointmentBookService`, `EncounterStartService`, `EncounterDischargeService`
    - `cdshooks/shared/` - Shared CDS Hooks utilities
    - `common/` - Shared utilities (`PlanDefinitionService`, `FhirCodeExtractor`, `FhirUtil`, `CrdConstants`)
    - `config/` - Spring configuration
    - `cql/` - CQL file resolution and ELM compilation
    - `datainitializer/` - Startup resource loading and CQL compilation
    - `dtr/` - DTR questionnaire pipeline (resolver, assemblers, response builder, session store, `DtrConstants`)
    - `pas/` - PAS `$submit` and `$inquire` operations: validation, coverage evaluation, response building, pended resolution
    - `providers/` - Custom FHIR resource providers
    - `scenarios/` - Test scenario generation from library PlanDefinitions, with sub-packages `crd/`, `dtr/`, `pas/`

### 2. Frontend (`frontend`)
- **Path**: `frontend/`
- **Type**: TanStack React Router SPA
- **Description**: Contains a frontend application for viewing and searching FHIR resources from the server.

### 3. Documentation (`docs`)
- **Path**: `docs/`
- **Type**: Markdown files
- **Description**: Contains project documentation, requirements, and design documents. Built using MkDocs.

### 4. Library (`library`)
- **Path**: `library/`
- **Type**: FHIR Library resources and CQL files
- **Description**: Contains CQL libraries and associated FHIR resources for clinical decision support.
- **Build Integration**:
  - Maven copies `library/` contents to `target/classes/library/` during build
  - For production, resources are bundled into the JAR/WAR
  - External libraries from other repositories can also be bundled via Maven resources
- **Structure**:
  - Each subdirectory represents a library (e.g., `HomeOxygenTherapy/`)
  - `*.cql` files contain the CQL logic
  - `Library-*.json` files are FHIR Library resources that reference the CQL files
  - `PlanDefinition-*.json` files define how the libraries are used
- **CQL File Resolution**:
  - Library JSON files can reference CQL files using `content.url` with just the filename
  - The CQL file must be in the same directory as the Library JSON
  - During server startup, the CQL content is automatically loaded and embedded
  - Alternatively, Library resources can use base64-encoded `content.data` directly

---

## Test Scenario Generation

Test request fixtures for CRD, DTR, and PAS are auto-generated from library PlanDefinition metadata rather than hand-maintained as JSON files.

### How It Works

1. **`LibraryScenarioScanner`** scans the `library/` directory for PlanDefinition + Questionnaire pairs, extracting `ScenarioMetadata` (focus codes, hook triggers, order types, questionnaire URLs)
2. Each IG has a scenario service and request builder pair in `scenarios/crd/`, `scenarios/dtr/`, `scenarios/pas/` sub-packages that transform metadata into valid request JSON
3. **`TestRequestFileGenerator`** runs at build time (`process-classes` phase via Maven exec plugin) and writes generated files to `target/test-requests/`

### Build Output

```
target/test-requests/
  dtr/
    home-oxygen-therapy-canonical-request.json
    home-oxygen-therapy-order-request.json
    ...
  crd/
    order-sign/
      home-oxygen-therapy-order-sign.json
      ...
    order-select/
      ...
    appointment-book/
      ...
    order-dispatch/
      ...
```

### Runtime API

Spring services (`CrdScenarioService`, `DtrScenarioService`, `PasScenarioService`) expose the same generated content via REST endpoints. These scan the FHIR database at request time, so they reflect the current server state.

| Endpoint | Description |
|----------|-------------|
| `GET /api/crd/scenarios` | List all CRD test scenarios |
| `GET /api/crd/scenarios/{id}/hooks/{hookName}` | Raw CDS Hooks request JSON for a hook variant |
| `GET /api/dtr/scenarios` | List all DTR test scenarios |
| `GET /api/dtr/scenarios/{id}/requests/{type}` | Raw FHIR Parameters JSON for a DTR variant |
| `GET /api/pas/scenarios` | List all PAS test scenarios |
| `GET /api/pas/scenarios/{id}/variants/{variantId}` | Raw FHIR Bundle JSON for a PAS variant |

### Adding New Scenarios

Adding a new PlanDefinition + Questionnaire to `library/` automatically generates DTR and CRD test fixtures at build time and makes them available via the REST API. No manual fixture authoring needed.

### Hand-Crafted Fixtures

Some test fixtures remain hand-crafted in `src/test/resources/cdshooks/` because they test edge cases not derivable from PlanDefinition metadata: error/validation scenarios, multi-order requests, encounter-based hooks, and visit-limit exceeded cases.

---

## Implementation Guide References

This server implements the Da Vinci Burden Reduction implementation guides. Always consult these when implementing features:

| IG | Build URL | Key Sections |
|----|-----------|--------------|
| **CRD** (Coverage Requirements Discovery) | https://hl7.org/fhir/us/davinci-crd/2.2.1/en/ | [Hooks](https://hl7.org/fhir/us/davinci-crd/2.2.1/en/hooks.html), [Cards](https://hl7.org/fhir/us/davinci-crd/2.2.1/en/cards.html), [CodeSystem](https://hl7.org/fhir/us/davinci-crd/2.2.1/en/CodeSystem-temp.html) |
| **DTR** (Documentation Templates and Rules) | https://hl7.org/fhir/us/davinci-dtr/2.2.0/en/ | [Specification](https://hl7.org/fhir/us/davinci-dtr/2.2.0/en/specification.html), [Expected Systems](https://hl7.org/fhir/us/davinci-dtr/2.2.0/en/index.html#expected-systems) |
| **PAS** (Prior Authorization Support) | https://hl7.org/fhir/us/davinci-pas/2.2.1/en/ | [Specification](https://hl7.org/fhir/us/davinci-pas/2.2.1/en/specification.html) |
| **CDex** (Clinical Data Exchange) | https://hl7.org/fhir/us/davinci-cdex/STU2.1/ | [Background](https://hl7.org/fhir/us/davinci-cdex/STU2.1/background.html) |
| **CDS Hooks** | https://cds-hooks.org/specification/current/ | [Discovery](https://cds-hooks.org/specification/current/#discovery), [HTTP Response](https://cds-hooks.org/specification/current/#http-response) |

**Important**: This server is a **payer** implementation. It does NOT implement provider/EHR-side functionality like DTR SMART apps.

---

## CDS Hooks Architecture

### Key Files

| Component | Location | Purpose |
|-----------|----------|---------|
| Hook Services | `server/src/main/java/org/hl7/davinci/cdshooks/services/` | Individual hook implementations (OrderSelectService, OrderSignService, etc.) |
| Shared Logic | `server/src/main/java/org/hl7/davinci/cdshooks/shared/` | Base classes, resource resolution, coverage info handling |
| Configuration | `server/src/main/java/org/hl7/davinci/cdshooks/CdsHooksConfig.java` | Spring configuration for CDS hooks |
| CRD Scenarios | `server/src/main/java/org/hl7/davinci/cdshooks/CrdScenarioService.java` | Generates CRD test scenarios from PlanDefinition metadata |
| CRD Request Builder | `server/src/main/java/org/hl7/davinci/scenarios/CrdRequestBuilder.java` | Builds CDS Hooks request JSON from ScenarioMetadata |
| CRD REST API | `server/src/main/java/org/hl7/davinci/api/CrdScenarioController.java` | REST endpoints under `/api/crd/` |

### Adding a New CDS Hook

1. Create a new service class extending `CdsServiceBase`
2. Annotate with `@CdsService` specifying hook name, prefetch templates
3. Implement `getHookName()`, `validateResourceContext()`, `selectContextResources()`
4. The service is auto-discovered via Spring component scanning

### Card Type Codes

Use `CrdConstants.CARD_TYPE_SYSTEM` for `source.topic`:
- `coverage-info`, `insurance`, `network`, `cost`, `therapy-alternatives-req`, etc.
- See [CRD CardType ValueSet](https://build.fhir.org/ig/HL7/davinci-crd/en/ValueSet-cardType.html)

---

## FHIR Operation Endpoints

| Provider | Operation | Notes |
|----------|-----------|-------|
| `ClaimSubmitProvider` | `Claim/$submit` | PAS prior authorization submission |
| `ClaimInquiryProvider` | `Claim/$inquire` | PAS authorization status inquiry |
| `QuestionnairePackageProvider` | `Questionnaire/$questionnaire-package` | DTR questionnaire packaging |
| `DtrQuestionnaireNextQuestionProvider` | `Questionnaire/$next-question` | DTR adaptive questionnaire |
| `LogQuestionnaireErrorsProvider` | `Questionnaire/$log-questionnaire-errors` | Stub -- throws `NotImplementedOperationException` |

---

## PAS Architecture

### Key Files

| Component | Location | Purpose |
|-----------|----------|---------|
| Submit Provider | `providers/ClaimSubmitProvider.java` | Thin REST layer for `$submit` |
| Inquiry Provider | `providers/ClaimInquiryProvider.java` | Thin REST layer for `$inquire` |
| Submit Service | `pas/PasSubmitService.java` | Orchestrates submit: validates, detects type, routes to handlers, stores Claim, builds response |
| Inquiry Service | `pas/PasInquiryService.java` | Query-by-example search against stored ClaimResponses |
| Bundle Validator | `pas/PasBundleValidator.java` | Validates request bundles per PAS IG structural constraints |
| Reference Resolver | `pas/PasBundleReferenceResolver.java` | Resolves Patient/Organization/Coverage references from bundles |
| Coverage Evaluator | `pas/PasCoverageEvaluator.java` | Bridges CRD PlanDefinitions to X12 review action codes (A1/A3/A4) |
| Response Builder | `pas/PasResponseBuilder.java` | Constructs conformant ClaimResponse bundles with auth numbers |
| Pended Resolution | `pas/PasPendedResolutionService.java` | Schedules one-shot auto-approval tasks for pended items |
| Extensions | `pas/PasExtensions.java` | PAS extension URLs, profile URLs, X12 review codes, builder helpers |
| Properties | `pas/PasProperties.java` | Config: `pas.pended-resolution-delay-seconds`, `pas.authorization-number-prefix` |

### PAS Submit Flow

1. `PasBundleValidator` validates the request bundle structure
2. `PasBundleReferenceResolver` resolves Patient/Organization/Coverage from the bundle
3. `PasSubmitService` detects submission type (INITIAL/RENEWAL/UPDATE/CANCEL) via structural Claim properties (presence of `Claim.related`), not `meta.profile`
4. Routes to type-specific handlers
5. `PasCoverageEvaluator` evaluates coverage using the same PlanDefinitions that drive CDS Hook cards, translating CRD outcomes to X12 review action codes: A1=Certified, A3=Not Certified, A4=Pended
6. `PasResponseBuilder` constructs the ClaimResponse bundle with authorization numbers
7. Items evaluated as A4 (Pended) are tagged with an internal meta tag; `PasPendedResolutionService` schedules one-shot tasks to auto-approve after `pas.pended-resolution-delay-seconds`

### Pended Resolution

- Pended ClaimResponses are tagged with `http://example.org/fhir/us/davinci-pas/internal-tags` / `pended-resolution`
- `PasPendedResolutionService` uses `TaskScheduler` to schedule one-shot resolution tasks
- On server restart, it rescans for tagged ClaimResponses and reschedules or immediately resolves them
- Tags are removed via `metaDeleteOperation` (necessary because HAPI JPA treats tags as additive across versions)

---

## PlanDefinition and CQL Authoring

### Creating a New Rule

1. Create a directory under `library/` (e.g., `library/MyNewRule/`)
2. Create the CQL file (`MyNewRule.cql`) with coverage logic
3. Create a Library resource (`Library-MyNewRule.json`) referencing the CQL
4. Create a PlanDefinition (`PlanDefinition-MyNewRule.json`) with:
   - `useContext` for order codes (focus) and payor identifiers (program)
   - `trigger` with `type: named-event` and `name: order-select` or `order-sign`
   - `action` referencing the Library

### PlanDefinition Matching

PlanDefinitions are matched based on:
- **Order code**: `useContext` with `code.code = "focus"` and value matching the order's code
- **Payor identifier**: `useContext` with `code.code = "program"` and value matching Coverage.payor
- **Hook type**: `action.trigger.name` matching the hook (e.g., `order-select`)

### General Guidelines for CQL

- Documentation for CQL authoring can be found at the following links:
  - https://cql.hl7.org/02-authorsguide.html
  - https://cql.hl7.org/03-developersguide.html
  - https://cql.hl7.org/09-b-cqlreference.html
- Shared CQL libraries are located in `library/common/` and should be used as much as possible to avoid duplication
- Newly identified redundant CQL should be refactored into `library/common/` as appropriate
- If possible, add symlinks to the new CQL in the `input/cql/` directory for VS Code extension support and edit these instead of the originals

### CQL Output for Coverage Information

CQL should output a FHIR Extension matching [ext-coverage-information](https://build.fhir.org/ig/HL7/davinci-crd/en/StructureDefinition-ext-coverage-information.html). The extension is extracted from the RequestGroup returned by PlanDefinition/$apply.


## IG Constants

FHIR URL constants (profiles, extensions, code systems) are organized by implementation guide into dedicated classes. New constants should be placed in the class matching their IG.

| IG | Class | Package | Contents |
|----|-------|---------|----------|
| **CRD** | `CrdConstants` | `common` | Coverage info extension, doc reason code system, card type system |
| **DTR** | `DtrConstants` | `dtr` | Questionnaire/QR profiles, DTR + SDC extensions, CQL expression URLs, adaptive mode header, canonical prefixes |
| **PAS** | `PasExtensions` | `pas` | PAS extension URLs, profile URLs, X12 review codes, extension builder helpers |

`CrdConstants` is in `common/` because CRD constants are referenced from both the `cdshooks` and `dtr` packages. DTR and PAS constants live within their respective packages.

## Configuration

- Main: `server/src/main/resources/application.yaml`
- CDS profile: `server/src/main/resources/application-cds.yaml` (loads CQL IGs, stricter ValueSet expansion)
- Test: `server/src/test/resources/application-test.yaml`
- VSAC integration: set `VSAC_API_KEY` env var (bean activates conditionally)
- DTR adaptive config: `dtr.adaptive.next-question-url`
- DTR warmup: `dtr.valueset-warmup.enabled` (pre-expands ValueSets at startup)
- PAS config: `pas.pended-resolution-delay-seconds` (default 15s), `pas.authorization-number-prefix` (default `AUTH-`)

### Maven Profiles

| Profile | Default | Purpose |
|---------|---------|---------|
| `boot` | Yes | Spring Boot embedded Tomcat (development) |
| `jetty` | No | Replace Tomcat with Jetty (`mvn -Pjetty spring-boot:run`) |

