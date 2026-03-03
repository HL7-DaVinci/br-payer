# Testing the setup

After the server finishes starting, check that the burden reduction features are working. These steps go beyond "is the FHIR server up" and confirm that the CRD, DTR, and PAS implementations loaded correctly.

## CDS Hooks discovery

```bash
curl http://localhost:8080/cds-services
```

The response should list all registered hooks: `order-select`, `order-sign`, `order-dispatch`, `appointment-book`, `encounter-start`, and `encounter-discharge`.

## Scenario APIs

Each IG has a scenario endpoint that returns test request payloads that are generated from the resources currently loaded in the database:

```bash
curl http://localhost:8080/api/crd/scenarios
curl http://localhost:8080/api/dtr/scenarios
curl http://localhost:8080/api/pas/scenarios
```

Each should return a JSON array with at least one entry. Empty arrays mean the library resources did not load into the database.

## Send a test CDS Hook request

Pick a scenario from the CRD API and fire it at a hook endpoint:

```bash
# Get the order-sign request for a scenario
curl http://localhost:8080/api/crd/scenarios/home-oxygen-therapy/hooks/order-sign -o hook-request.json

# Send it
curl -X POST http://localhost:8080/cds-services/order-sign-crd \
  -H "Content-Type: application/json" \
  -d @hook-request.json
```

The response should contain CDS cards with coverage information. If you get cards back, CRD is working end-to-end: the PlanDefinition matched, the CQL evaluated, and the card builder produced output.

## Frontend

If you started the frontend, open `http://localhost:3000` (dev) or `http://localhost:8080` (Docker). The test bed UI lets you browse scenarios and send requests.
