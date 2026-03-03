# Library modules

Each subdirectory under `library/` is a module. Here is the `HomeOxygenTherapy` module as an example:

```
library/HomeOxygenTherapy/
  PlanDefinition-home-oxygen-therapy.json
  Library-home-oxygen-therapy.json
  HomeOxygenTherapyRule.cql
  Questionnaire-home-o2-std-questionnaire.json
  ValueSet-home-oxygen-therapy.json
```

## File roles

**PlanDefinition** (`PlanDefinition-home-oxygen-therapy.json`). The trigger configuration. Declares which order codes activate the rule (via `useContext` with code `"focus"`), which payor IDs it applies to (`useContext` with code `"program"`), and which CDS hooks fire it (via `action.trigger` with `type: named-event`). Points to the Library resource that contains the logic.

**Library** (`Library-home-oxygen-therapy.json`). A thin FHIR wrapper around the CQL file. Its `content` element uses either `content.url` (a filename resolved from the same directory) or `content.data` (base64-encoded CQL). At startup, the server resolves the file reference, compiles CQL to ELM, and stores the result.

**CQL** (`HomeOxygenTherapyRule.cql`). The coverage logic. Evaluates whether the rule applies, generates card text, and produces coverage information values. See [CQL expression contract](#cql-expression-contract) below.

**Questionnaire** (`Questionnaire-home-o2-std-questionnaire.json`). The DTR form. Defines what information the provider needs to fill out. Only needed if the rule requires documentation collection.

**ValueSet** (`ValueSet-home-oxygen-therapy.json`). Coded value lists used by the CQL and Questionnaire. Include one per distinct set of codes the module references.

## Naming conventions

These naming rules matter because the scenario generator and resource linker rely on them:

| Rule | Example |
|------|---------|
| PlanDefinition `name` is PascalCase | `HomeOxygenTherapy` |
| PlanDefinition `name` becomes the kebab-case scenario ID | `home-oxygen-therapy` |
| Questionnaire `name` must start with the PlanDefinition `name` | `HomeOxygenTherapy...` |
| Questionnaire `url` starts with `http://example.org/fhir/Questionnaire/` | `http://example.org/fhir/Questionnaire/HomeOxygenTherapy` |
| Library `content.url` is a filename in the same directory | `HomeOxygenTherapyRule.cql` |

## Order type inference

The scenario generator infers the FHIR order resource type from the code system used in `useContext[focus]`:

| Code system | Inferred resource type |
|------------|----------------------|
| HCPCS (`http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets`) | DeviceRequest |
| RxNorm (`http://www.nlm.nih.gov/research/umls/rxnorm`) | MedicationRequest |
| CPT (`http://www.ama-assn.org/go/cpt`) | ServiceRequest |
| SNOMED CT (`http://snomed.info/sct`) | ServiceRequest |

To override the default, add a `useContext` entry with `code.code = "task"` and a value indicating the resource type.

## CQL expression contract

The CQL library must define these expressions. The PlanDefinition's `action` block references them by name:

### Required

| Expression | Return type | Purpose |
|-----------|-------------|---------|
| `"Rule Applies"` | Boolean | Applicability condition. Does this rule fire for the current order? |
| `"Get Summary"` | String | One-line card title (shown to the provider) |
| `"Get Detail"` | String | Longer card description |

### Optional (coverage information)

These populate the [CRD coverage information extension](https://build.fhir.org/ig/HL7/davinci-crd/StructureDefinition-ext-coverage-information.html):

| Expression | Return type | Purpose |
|-----------|-------------|---------|
| `"Coverage Info Covered"` | String | `"covered"`, `"not-covered"`, or `"conditional"` |
| `"Coverage Info PA Required"` | Boolean | Whether prior authorization is required |
| `"Coverage Info Doc Purpose"` | String | `"clinical"`, `"admin"`, or `"both"` (null if no documentation needed) |

### Example

From `HomeOxygenTherapyRule.cql`:

```cql
define "Rule Applies":
  exists (DeviceRequest.code.coding C where C in "Home Oxygen Therapy Codes")

define "Get Summary":
  'Prior Authorization Required for Home Oxygen Therapy'

define "Get Detail":
  'Home oxygen therapy (codes ' +
  Combine(DeviceRequest.code.coding C return C.code, ', ') +
  ') requires prior authorization. Please complete the required documentation.'
```

## Shared CQL

Common helper libraries live in `library/common/` (e.g., `CRDHelpers.cql`, `CoverageExtensions.cql`) and `library/dtr/`. Use `include` statements to pull them into the module's CQL rather than duplicating logic.
