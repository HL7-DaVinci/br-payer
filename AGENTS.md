<!-- nx configuration start-->
<!-- Leave the start & end comments to automatically receive updates. -->

# General Guidelines for working with Nx

- When running tasks (for example build, lint, test, e2e, etc.), always prefer running the task through `nx` (i.e. `nx run`, `nx run-many`, `nx affected`) instead of using the underlying tooling directly
- You have access to the Nx MCP server and its tools, use them to help the user
- When answering questions about the repository, use the `nx_workspace` tool first to gain an understanding of the workspace architecture where applicable.
- When working in individual projects, use the `nx_project_details` mcp tool to analyze and understand the specific project structure and dependencies
- For questions around nx configuration, best practices or if you're unsure, use the `nx_docs` tool to get relevant, up-to-date docs. Always use this instead of assuming things about nx configuration
- If the user needs help with an Nx configuration or project graph error, use the `nx_workspace` tool to get any errors


<!-- nx configuration end-->

# Workspace Overview

This is an Nx workspace designed to host a FHIR application stack.

## Projects

### 1. Server (`server`)
- **Path**: `server/`
- **Type**: Java / Maven / HAPI FHIR
- **Description**: The backend FHIR server.
- **Structure**:
  - `src/main/java`: Java source code.
  - `src/main/resources`: Configuration files (`application.yaml`, `logback.xml`) and sample FHIR resources.
  - `src/main/java/ca/uhn/fhir`: HAPI starter code that should not be modified.
  - `src/main/java/org/hl7/davinci`: Custom implementation code should be placed here.
    - `providers/`: FHIR resource providers for custom operations
  - `src/main/java/ca/uhn/fhir/jpa/starter/CustomServerConfig.java`: Custom Spring configuration that scans for the custom code in `org.hl7.davinci` so that code in the starter structure does not need to be modified.
  - `pom.xml`: Maven build configuration.
  - `Dockerfile`: Docker build instructions for the server.

### 2. Frontend (`frontend`) - *Upcoming*
- **Path**: `frontend/`
- **Type**: Next.js Static Web App
- **Status**: Planned/Not yet implemented.
- **Description**: Will contain the user interface for the application.

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

## Implementation Guide References

This server implements the Da Vinci Burden Reduction implementation guides. Always consult these when implementing features:

| IG | Build URL | Key Sections |
|----|-----------|--------------|
| **CRD** (Coverage Requirements Discovery) | https://build.fhir.org/ig/HL7/davinci-crd/en/ | [Hooks](https://build.fhir.org/ig/HL7/davinci-crd/en/hooks.html), [Cards](https://build.fhir.org/ig/HL7/davinci-crd/en/cards.html), [CodeSystem](https://build.fhir.org/ig/HL7/davinci-crd/en/CodeSystem-temp.html) |
| **DTR** (Documentation Templates and Rules) | https://build.fhir.org/ig/HL7/davinci-dtr/en/ | [Specification](https://build.fhir.org/ig/HL7/davinci-dtr/en/specification.html), [Expected Systems](https://build.fhir.org/ig/HL7/davinci-dtr/en/index.html#expected-systems) |
| **PAS** (Prior Authorization Support) | https://build.fhir.org/ig/HL7/davinci-pas/en/ | [Specification](https://build.fhir.org/ig/HL7/davinci-pas/en/specification.html) |
| **CDS Hooks** | https://cds-hooks.org/specification/current/ | [Discovery](https://cds-hooks.org/specification/current/#discovery), [HTTP Response](https://cds-hooks.org/specification/current/#http-response) |

**Important**: This server is a **payer** implementation. It does NOT implement provider/EHR-side functionality like DTR SMART apps.

---

## CDS Hooks Architecture

### Key Files

| Component | Location | Purpose |
|-----------|----------|---------|
| Hook Services | `server/src/main/java/org/hl7/davinci/cdshooks/services/` | Individual hook implementations (OrderSelectService, OrderSignService) |
| Shared Logic | `server/src/main/java/org/hl7/davinci/cdshooks/shared/` | Base classes, resource resolution, coverage info handling |
| Configuration | `server/src/main/java/org/hl7/davinci/cdshooks/CdsHooksConfig.java` | Spring configuration for CDS hooks |

### Adding a New CDS Hook

1. Create a new service class extending `CdsServiceBase`
2. Annotate with `@CdsService` specifying hook name, prefetch templates
3. Implement `getHookName()`, `validateResourceContext()`, `selectContextResources()`
4. The service is auto-discovered via Spring component scanning

### Card Type Codes

Use codes from `http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp` for `source.topic`:
- `coverage-info`, `insurance`, `network`, `cost`, `therapy-alternatives-req`, etc.
- See [CRD CardType ValueSet](https://build.fhir.org/ig/HL7/davinci-crd/en/ValueSet-cardType.html)

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


## Key Constraints

1. **Do NOT modify HAPI starter code** in `src/main/java/ca/uhn/fhir/` - place custom code in `org.hl7.davinci`
2. **Payer-only scope** - This server implements payer operations; DTR app launch URLs are provider-side concerns
3. **CodeSystem for card types** - Use `http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp`, not custom codes
4. **Coverage extension** - Always include required elements: `coverage`, `covered`, `date`, `coverage-assertion-id`
