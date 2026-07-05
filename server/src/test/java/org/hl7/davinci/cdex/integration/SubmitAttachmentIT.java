package org.hl7.davinci.cdex.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.hl7.davinci.cdex.CdexConstants;
import org.hl7.davinci.cdex.SubmitAttachmentService;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations.DocumentReferenceStatus;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
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
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsServiceRegistry;

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
        "spring.datasource.url=jdbc:h2:mem:dbr4-cdex-submit-attachment-it",
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
class SubmitAttachmentIT {

  @LocalServerPort
  private int port;

  @Autowired
  private SubmitAttachmentService submitService;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private ICdsServiceRegistry cdsServiceRegistry;

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
  }

  private static ParametersParameterComponent attachmentWith(Resource content) {
    ParametersParameterComponent attachment = new ParametersParameterComponent();
    attachment.setName(CdexConstants.PARAM_ATTACHMENT);
    attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CONTENT).setResource(content);
    return attachment;
  }

  private static DocumentReference documentReference() {
    return new DocumentReference()
        .setStatus(DocumentReferenceStatus.CURRENT)
        .setSubject(new Reference("Patient/it-subject"));
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  @DisplayName("$submit-attachment associates attachments with a ClaimResponse matched by TrackingId")
  void associatesWithMatchingClaimResponse() {
    ClaimResponse cr = new ClaimResponse();
    cr.addIdentifier().setSystem("http://example.org/acn").setValue("ACN-IT-1");
    cr.setStatus(ClaimResponse.ClaimResponseStatus.ACTIVE);
    cr.setUse(ClaimResponse.Use.PREAUTHORIZATION);
    cr.setOutcome(ClaimResponse.RemittanceOutcome.QUEUED);
    cr.setPatient(new Reference("Patient/it-subject"));
    daoRegistry.getResourceDao(ClaimResponse.class).create(cr, new SystemRequestDetails());

    OperationOutcome outcome = submitService.submit(
        new Identifier().setSystem("http://example.org/acn").setValue("ACN-IT-1"),
        new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION), null,
        new Identifier().setValue("1407071236"), null,
        new Identifier().setValue("M-IT-1"), null,
        List.of(attachmentWith(documentReference())), null);

    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase().contains("associated"));
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  @DisplayName("$submit-attachment stores the attachment content and holds it when no claim matches")
  void heldForFutureWhenNoMatch() {
    OperationOutcome outcome = submitService.submit(
        new Identifier().setValue("ACN-NO-MATCH"),
        new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION), null,
        new Identifier().setValue("1407071236"), null,
        new Identifier().setValue("M-IT-2"), null,
        List.of(attachmentWith(documentReference())), null);

    assertTrue(outcome.getIssueFirstRep().getDiagnostics().toLowerCase().contains("held for future"));

    int stored = daoRegistry.getResourceDao(DocumentReference.class)
        .search(new ca.uhn.fhir.jpa.searchparam.SearchParameterMap(), new SystemRequestDetails())
        .getAllResources()
        .size();
    assertTrue(stored >= 1, "submitted DocumentReference should be persisted");
  }

  @Test
  @Timeout(value = 90, unit = TimeUnit.SECONDS)
  @DisplayName("$submit-attachment operation binds Parameters over HTTP and rejects a missing AttachTo with 400")
  void httpOperationRejectsMissingAttachTo() {
    FhirContext ctx = FhirContext.forR4();
    IGenericClient client = ctx.newRestfulGenericClient("http://localhost:" + port + "/fhir");

    Parameters in = new Parameters();
    in.addParameter().setName(CdexConstants.PARAM_TRACKING_ID).setValue(new Identifier().setValue("ACN-HTTP-1"));
    in.addParameter().setName(CdexConstants.PARAM_MEMBER_ID).setValue(new Identifier().setValue("M-HTTP-1"));
    in.addParameter().setName(CdexConstants.PARAM_ORGANIZATION_ID).setValue(new Identifier().setValue("1407071236"));
    in.addParameter(attachmentWith(documentReference()));

    assertThrows(InvalidRequestException.class, () ->
        client.operation()
            .onServer()
            .named("$" + CdexConstants.OPERATION_SUBMIT_ATTACHMENT)
            .withParameters(in)
            .execute());
  }
}
