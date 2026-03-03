# Burden Reduction Payer Reference Implementation

A reference FHIR server that implements three HL7 Da Vinci implementation guides for payer-side burden reduction: [CRD](https://build.fhir.org/ig/HL7/davinci-crd/) (Coverage Requirements Discovery), [DTR](https://build.fhir.org/ig/HL7/davinci-dtr/) (Documentation Templates and Rules), and [PAS](https://build.fhir.org/ig/HL7/davinci-pas/) (Prior Authorization Support).

The server builds on [HAPI FHIR JPA Starter](https://github.com/hapifhir/hapi-fhir-jpaserver-starter), so standard FHIR operations (resource CRUD, search, metadata, Swagger UI) work out of the box. This documentation covers only what the burden reduction implementation adds.

## Quick links

- [Run the server](getting-started/running.md): three ways to start, from one command to Docker
- [Add clinical content](clinical-content/library-modules.md): write coverage rules without touching Java code
- [Test with scenarios](clinical-content/scenarios.md): auto-generated test requests for every rule module

## Implementation guide specs

| IG | Spec |
|----|------|
| CRD | <https://build.fhir.org/ig/HL7/davinci-crd/> |
| DTR | <https://build.fhir.org/ig/HL7/davinci-dtr/> |
| PAS | <https://build.fhir.org/ig/HL7/davinci-pas/> |
