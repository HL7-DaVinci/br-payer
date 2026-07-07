package org.hl7.davinci.scenarios.cdex.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.common.FhirConstants;
import org.hl7.davinci.pas.PasCommunicationRequestBuilder;
import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasExtensions;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.opencds.cqf.fhir.cr.hapi.config.CrCdsHooksConfig;
import org.opencds.cqf.fhir.cr.hapi.config.RepositoryConfig;
import org.opencds.cqf.fhir.cr.hapi.config.test.TestCdsHooksConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.config.NicknameServiceConfig;
import ca.uhn.fhir.jpa.starter.Application;
import ca.uhn.fhir.jpa.starter.cdshooks.StarterCdsHooksConfig;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

/**
 * End-to-end coverage for the CDex attachments testbed workflow: seeds a pended
 * ClaimResponse awaiting a questionnaire, drives the two /api/cdex read endpoints
 * over HTTP, submits the generated payload verbatim to $submit-attachment, and
 * asserts the item re-adjudicates from A4 (Pended) to A1 (Certified).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = {
        Application.class,
        NicknameServiceConfig.class,
        RepositoryConfig.class,
        TestCdsHooksConfig.class,
        CrCdsHooksConfig.class,
        StarterCdsHooksConfig.class,
        org.hl7.davinci.cdshooks.CdsHooksConfig.class
    },
    properties = {
        "spring.profiles.include=storageSettingsTest",
        "spring.datasource.url=jdbc:h2:mem:dbr4-cdex-scenario-it",
        "spring.jpa.properties.hibernate.search.backend.directory.type=local-heap",
        "hapi.fhir.enable_repository_validating_interceptor=false",
        "hapi.fhir.fhir_version=r4",
        "hapi.fhir.cr.enabled=true",
        "hapi.fhir.cdshooks.enabled=true",
        "spring.main.allow-bean-definition-overriding=true",
        "vsac.api-key=",
        "dtr.adaptive.next-question-url=http://localhost:8080/fhir/Questionnaire/$next-question"
    }
)
class CdexScenarioIT {

  @LocalServerPort
  private int port;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private ICdsServiceRegistry cdsServiceRegistry;

  private final FhirContext fhirContext = FhirContext.forR4();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  void setUpOnce() {
    await().atMost(120, TimeUnit.SECONDS)
        .pollInterval(2, TimeUnit.SECONDS)
        .until(() -> {
          try {
            var services = cdsServiceRegistry.getCdsServicesJson();
            return services != null && services.getServices() != null
                && !services.getServices().isEmpty();
          } catch (Exception e) {
            return false;
          }
        });
    seedPendedClaim();
  }

  private void seedPendedClaim() {
    Patient patient = new Patient();
    patient.setId("cdex-it-patient");
    patient.addIdentifier(new Identifier()
        .setType(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "MB", null)))
        .setValue("M-CDEX-IT-1"));
    daoRegistry.getResourceDao(Patient.class).update(patient, new SystemRequestDetails());

    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setId("q-cdex-it");
    questionnaire.setUrl("http://example.org/Questionnaire/CdexIt");
    questionnaire.setTitle("CDex IT Questionnaire");
    questionnaire.addItem().setLinkId("1").setText("Example question")
        .setType(Questionnaire.QuestionnaireItemType.STRING);
    daoRegistry.getResourceDao(Questionnaire.class).update(questionnaire, new SystemRequestDetails());

    CommunicationRequest commReq =
        PasCommunicationRequestBuilder.buildQuestionnaireRequest(1, "Patient/cdex-it-patient", "q-cdex-it",
            "http://example.org/Questionnaire/CdexIt");
    daoRegistry.getResourceDao(CommunicationRequest.class).update(commReq, new SystemRequestDetails());

    ClaimResponse cr = new ClaimResponse();
    cr.setId("cdex-it-pended");
    cr.getMeta().addTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE,
        "Pended Resolution");
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    cr.setUse(ClaimResponse.Use.PREAUTHORIZATION);
    cr.setOutcome(ClaimResponse.RemittanceOutcome.COMPLETE);
    cr.addIdentifier().setSystem("http://example.org/PATIENT_EVENT_TRACE_NUMBER")
        .setValue("ACN-CDEX-IT-1");
    cr.setPatient(new Reference("Patient/cdex-it-patient"));
    ClaimResponse.ItemComponent item = cr.addItem().setItemSequence(1);
    item.addAdjudication()
        .setCategory(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/adjudication", "submitted", null)))
        .addExtension(PasExtensions.buildReviewActionExtension(
            FhirConstants.REVIEW_CODE_A4, "Pending", null));
    cr.addCommunicationRequest(
        new Reference("CommunicationRequest/" + commReq.getIdElement().getIdPart()));
    daoRegistry.getResourceDao(ClaimResponse.class).update(cr, new SystemRequestDetails());
  }

  private String get(String path) throws Exception {
    HttpResponse<String> response = http.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "GET " + path + " body: " + response.body());
    return response.body();
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  @DisplayName("pended list, generated payload, and $submit-attachment resolve the claim to A1")
  void fullAttachmentWorkflow() throws Exception {
    String pendedJson = get("/api/cdex/pended");
    assertTrue(pendedJson.contains("cdex-it-pended"), pendedJson);
    assertTrue(pendedJson.contains("ACN-CDEX-IT-1"), pendedJson);
    assertTrue(pendedJson.contains("CDex IT Questionnaire"), pendedJson);

    String parametersJson = get("/api/cdex/pended/cdex-it-pended/submit-attachment");
    Parameters parameters = (Parameters) fhirContext.newJsonParser().parseResource(parametersJson);
    Identifier memberId = (Identifier) parameters.getParameter().stream()
        .filter(p -> "MemberId".equals(p.getName()))
        .findFirst().orElseThrow().getValue();
    assertEquals("M-CDEX-IT-1", memberId.getValue());

    IGenericClient client = fhirContext.newRestfulGenericClient("http://localhost:" + port + "/fhir");
    OperationOutcome outcome = client.operation()
        .onServer()
        .named("$submit-attachment")
        .withParameters(parameters)
        .returnResourceType(OperationOutcome.class)
        .execute();
    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase().contains("associated"),
        outcome.getIssueFirstRep().getDiagnostics());

    ClaimResponse resolved = daoRegistry.getResourceDao(ClaimResponse.class)
        .read(new IdType("ClaimResponse/cdex-it-pended"), new SystemRequestDetails());
    assertEquals(FhirConstants.REVIEW_CODE_A1,
        PasExtensions.extractReviewActionCode(resolved.getItemFirstRep()));

    CommunicationRequest completed = daoRegistry.getResourceDao(CommunicationRequest.class)
        .read(new IdType("CommunicationRequest/" + firstCommReqId(resolved)),
            new SystemRequestDetails());
    assertEquals(CommunicationRequest.CommunicationRequestStatus.COMPLETED, completed.getStatus());

    // After resolution the claim no longer appears in the pended list
    assertFalse(get("/api/cdex/pended").contains("cdex-it-pended"));
  }

  private String firstCommReqId(ClaimResponse cr) {
    return new IdType(cr.getCommunicationRequestFirstRep().getReference()).getIdPart();
  }
}
