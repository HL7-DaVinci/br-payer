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
