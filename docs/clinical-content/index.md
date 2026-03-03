# Clinical content library

The `library/` directory at the repository root is where all clinical coverage rules live. Each subdirectory is a self-contained module: a bundle of FHIR resources and CQL logic that defines when a rule fires, what documentation to collect, and what coverage determination to return.

Adding new rules does not require any Java code changes. Drop files into a new `library/` subdirectory, restart the server, and the rule is active across CRD, DTR, and PAS.

## How the pieces fit together

A **PlanDefinition** is the entry point. It declares which order codes trigger the rule, which payor IDs it applies to, and which CDS hooks activate it. The PlanDefinition points to a **Library** resource, which wraps a **CQL** file containing the actual logic. Does the rule apply? What should the card say? Is prior auth required?

For rules that need documentation collection, a **Questionnaire** defines what DTR asks the provider. **ValueSets** supply coded answer options.

## What each use case needs

A CRD-only module (coverage cards, no documentation collection) needs three files:

- `PlanDefinition-*.json`
- `Library-*.json`
- `*.cql`

A CRD + DTR module (coverage cards plus questionnaire) adds at least two more:

- `Questionnaire-*.json`
- `ValueSet-*.json` (one per coded answer list)

PAS works automatically. It reuses the same PlanDefinition and CQL to evaluate coverage for prior authorization requests.

## Next steps

- [Library modules](library-modules.md): file structure, naming rules, and the CQL expression contract
- [Scenarios and testing](scenarios.md): how the server generates test requests from the library modules
