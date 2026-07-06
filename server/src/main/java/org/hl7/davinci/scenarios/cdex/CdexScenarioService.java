package org.hl7.davinci.scenarios.cdex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hl7.davinci.cdex.CdexConstants;
import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasExtensions;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * Derives CDex attachment-workflow test data from live server state: pended
 * ClaimResponses and the CommunicationRequests that describe the documentation
 * they are waiting on. Mirrors the live-scan approach of PasScenarioService.
 */
@Service
public class CdexScenarioService {

  private static final Logger logger = LoggerFactory.getLogger(CdexScenarioService.class);

  private static final String MEMBER_ID_TYPE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v2-0203";
  private static final String MEMBER_ID_TYPE_CODE = "MB";

  private final DaoRegistry daoRegistry;

  public CdexScenarioService(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  public List<PendedClaimDto> getPendedClaims() {
    SearchParameterMap params = new SearchParameterMap();
    params.add("_tag", new TokenParam(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE));
    params.setLoadSynchronous(true);
    List<ClaimResponse> pended = daoRegistry.getResourceDao(ClaimResponse.class)
        .searchForResources(params, new SystemRequestDetails());
    return pended.stream()
        .filter(ClaimResponse::hasCommunicationRequest)
        .map(this::toDto)
        .toList();
  }

  public Optional<ClaimResponse> findPendedClaim(String claimResponseId) {
    try {
      ClaimResponse cr = daoRegistry.getResourceDao(ClaimResponse.class)
          .read(new IdType("ClaimResponse/" + claimResponseId), new SystemRequestDetails());
      boolean pended = cr.getMeta()
          .getTag(PasConstants.PENDED_TAG_SYSTEM, PasConstants.PENDED_TAG_CODE) != null;
      return pended && cr.hasCommunicationRequest() ? Optional.of(cr) : Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  public List<CommunicationRequest> loadDocumentationRequests(ClaimResponse cr) {
    List<CommunicationRequest> requests = new ArrayList<>();
    for (Reference ref : cr.getCommunicationRequest()) {
      if (!ref.hasReference()) {
        continue;
      }
      try {
        CommunicationRequest request = daoRegistry.getResourceDao(CommunicationRequest.class)
            .read(new IdType(ref.getReference()), new SystemRequestDetails());
        if (request != null) {
          requests.add(request);
        }
      } catch (RuntimeException e) {
        logger.debug("CommunicationRequest {} could not be read, skipping", ref.getReference());
      }
    }
    return requests;
  }

  private PendedClaimDto toDto(ClaimResponse cr) {
    Identifier trackingId = cr.getIdentifierFirstRep();
    List<PendedItemDto> items = cr.getItem().stream()
        .map(item -> new PendedItemDto(item.getItemSequence(),
            PasExtensions.extractReviewActionCode(item)))
        .toList();
    List<DocumentationRequestDto> requests = loadDocumentationRequests(cr).stream()
        .map(this::decode)
        .toList();
    Patient patient = readPatient(cr);
    return new PendedClaimDto(
        cr.getIdElement().getIdPart(),
        trackingId.hasSystem() ? trackingId.getSystem() : null,
        trackingId.hasValue() ? trackingId.getValue() : null,
        cr.hasPatient() ? cr.getPatient().getReference() : null,
        patientDisplay(patient),
        memberId(patient),
        cr.hasCreated() ? cr.getCreatedElement().getValueAsString() : null,
        items,
        requests);
  }

  private DocumentationRequestDto decode(CommunicationRequest request) {
    String payloadValue = null;
    if (request.hasPayload()
        && request.getPayloadFirstRep().getContent() instanceof StringType value) {
      payloadValue = value.getValue();
    }
    String trn = request.getIdentifier().stream()
        .filter(id -> PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM.equals(id.getSystem()))
        .map(Identifier::getValue)
        .findFirst()
        .orElse(null);
    Integer lineNumber = serviceLineNumber(request);
    String status = request.getStatus() == null ? null : request.getStatus().toCode();

    boolean isQuestionnaire = PasConstants.LOINC_QUESTIONNAIRE_REQUEST.equals(payloadValue);
    if (!isQuestionnaire) {
      return new DocumentationRequestDto(request.getIdElement().getIdPart(),
          "attachment-code", payloadValue, null, null, trn, lineNumber, status);
    }

    Questionnaire questionnaire = readQuestionnaire(trn);
    String canonical = questionnaire == null ? null : questionnaire.getUrl();
    String name = null;
    if (questionnaire != null) {
      name = questionnaire.hasTitle() ? questionnaire.getTitle() : questionnaire.getName();
    }
    return new DocumentationRequestDto(request.getIdElement().getIdPart(),
        "questionnaire", null, canonical, name, trn, lineNumber, status);
  }

  Questionnaire readQuestionnaire(String logicalId) {
    if (logicalId == null || logicalId.isBlank()) {
      return null;
    }
    try {
      return daoRegistry.getResourceDao(Questionnaire.class)
          .read(new IdType("Questionnaire/" + logicalId), new SystemRequestDetails());
    } catch (RuntimeException e) {
      logger.debug("Questionnaire/{} could not be read", logicalId);
      return null;
    }
  }

  private Integer serviceLineNumber(CommunicationRequest request) {
    Extension ext = request.getExtensionByUrl(PasConstants.EXT_SERVICE_LINE_NUMBER);
    if (ext == null || !(ext.getValue() instanceof PrimitiveType<?> value)
        || !value.hasPrimitiveValue()) {
      return null;
    }
    try {
      return Integer.parseInt(value.getValueAsString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Patient readPatient(ClaimResponse cr) {
    if (!cr.hasPatient() || !cr.getPatient().hasReference()) {
      return null;
    }
    try {
      return daoRegistry.getResourceDao(Patient.class)
          .read(new IdType(cr.getPatient().getReference()), new SystemRequestDetails());
    } catch (RuntimeException e) {
      logger.debug("Patient {} could not be read", cr.getPatient().getReference());
      return null;
    }
  }

  private String patientDisplay(Patient patient) {
    if (patient == null || !patient.hasName()) {
      return null;
    }
    String name = patient.getNameFirstRep().getNameAsSingleString();
    return name == null || name.isBlank() ? null : name;
  }

  String memberId(Patient patient) {
    if (patient == null) {
      return null;
    }
    return patient.getIdentifier().stream()
        .filter(id -> id.hasType()
            && id.getType().hasCoding(MEMBER_ID_TYPE_SYSTEM, MEMBER_ID_TYPE_CODE))
        .findFirst()
        .or(() -> patient.getIdentifier().stream().filter(Identifier::hasValue).findFirst())
        .map(Identifier::getValue)
        .orElse(null);
  }

  // Fallback NPI used when the requestor organization cannot be resolved; matches the
  // example NPI used throughout the existing CDex tests.
  static final String DEFAULT_ORGANIZATION_ID = "1407071236";
  static final String DEFAULT_MEMBER_ID = "UNKNOWN-MEMBER";

  /**
   * Builds a ready-to-send $submit-attachment Parameters for a pended ClaimResponse.
   * Includes one Attachment per open (non-completed) documentation request; when
   * trnFilter is non-empty, only requests whose trace number is in the filter are
   * included. Returns empty when the claim does not exist or is not awaiting
   * documentation.
   */
  public Optional<Parameters> buildSubmitAttachment(String claimResponseId,
      Set<String> trnFilter, boolean finalSubmission) {

    Optional<ClaimResponse> found = findPendedClaim(claimResponseId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ClaimResponse cr = found.get();
    String patientReference = cr.hasPatient() ? cr.getPatient().getReference() : null;

    Parameters parameters = new Parameters();
    parameters.addParameter().setName(CdexConstants.PARAM_TRACKING_ID)
        .setValue(cr.getIdentifierFirstRep().copy());
    parameters.addParameter().setName(CdexConstants.PARAM_ATTACH_TO)
        .setValue(new CodeType(CdexConstants.CLAIM_USE_PREAUTHORIZATION));
    String memberId = memberId(readPatient(cr));
    parameters.addParameter().setName(CdexConstants.PARAM_MEMBER_ID)
        .setValue(new Identifier().setValue(memberId != null ? memberId : DEFAULT_MEMBER_ID));
    parameters.addParameter().setName(CdexConstants.PARAM_ORGANIZATION_ID)
        .setValue(new Identifier().setValue(organizationId(cr)));

    for (CommunicationRequest request : loadDocumentationRequests(cr)) {
      if (request.getStatus() == CommunicationRequestStatus.COMPLETED) {
        continue;
      }
      DocumentationRequestDto decoded = decode(request);
      if (!trnFilter.isEmpty() && !trnFilter.contains(decoded.trn())) {
        continue;
      }
      ParametersParameterComponent attachment = attachmentFor(decoded, patientReference);
      if (attachment != null) {
        parameters.addParameter(attachment);
      }
    }

    parameters.addParameter().setName(CdexConstants.PARAM_FINAL)
        .setValue(new BooleanType(finalSubmission));
    return Optional.of(parameters);
  }

  private ParametersParameterComponent attachmentFor(DocumentationRequestDto request,
      String patientReference) {

    Resource content;
    if ("questionnaire".equals(request.type())) {
      Questionnaire questionnaire = readQuestionnaire(request.trn());
      if (questionnaire == null) {
        logger.warn("Questionnaire for TRN {} could not be resolved; skipping attachment",
            request.trn());
        return null;
      }
      content = QuestionnaireResponseGenerator.generate(questionnaire, patientReference);
    } else {
      content = stubDocumentReference(request.code(), patientReference);
    }

    ParametersParameterComponent attachment = new ParametersParameterComponent();
    attachment.setName(CdexConstants.PARAM_ATTACHMENT);
    if (request.lineNumber() != null) {
      attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_LINE_ITEM)
          .setValue(new PositiveIntType(request.lineNumber()));
    }
    if (request.code() != null) {
      attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CODE)
          .setValue(new CodeableConcept().addCoding(
              new Coding(PasConstants.LOINC_SYSTEM, request.code(), null)));
    }
    attachment.addPart().setName(CdexConstants.PARAM_ATTACHMENT_CONTENT).setResource(content);
    return attachment;
  }

  private DocumentReference stubDocumentReference(String code, String patientReference) {
    DocumentReference doc = new DocumentReference();
    doc.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
    if (patientReference != null) {
      doc.setSubject(new Reference(patientReference));
    }
    if (code != null) {
      doc.getType().addCoding(new Coding(PasConstants.LOINC_SYSTEM, code, null));
    }
    doc.addContent().getAttachment()
        .setContentType("text/plain")
        .setData(("Example clinical documentation for requested code " + code)
            .getBytes(StandardCharsets.UTF_8));
    return doc;
  }

  private String organizationId(ClaimResponse cr) {
    Reference requestor = cr.getRequestor();
    if (requestor != null && requestor.hasIdentifier() && requestor.getIdentifier().hasValue()) {
      return requestor.getIdentifier().getValue();
    }
    if (requestor != null && requestor.hasReference()
        && new IdType(requestor.getReference()).getResourceType() != null) {
      try {
        var resource = daoRegistry.getResourceDao(new IdType(requestor.getReference()).getResourceType())
            .read(new IdType(requestor.getReference()), new SystemRequestDetails());
        if (resource instanceof Organization org && org.hasIdentifier()
            && org.getIdentifierFirstRep().hasValue()) {
          return org.getIdentifierFirstRep().getValue();
        }
      } catch (RuntimeException e) {
        logger.debug("Requestor {} could not be resolved for OrganizationId",
            requestor.getReference());
      }
    }
    return DEFAULT_ORGANIZATION_ID;
  }

  // ===== DTOs =====

  public record PendedClaimDto(
      String claimResponseId,
      String trackingIdSystem,
      String trackingIdValue,
      String patientReference,
      String patientDisplay,
      String memberId,
      String created,
      List<PendedItemDto> items,
      List<DocumentationRequestDto> documentationRequests) {
  }

  public record PendedItemDto(int sequence, String reviewActionCode) {
  }

  public record DocumentationRequestDto(
      String communicationRequestId,
      String type,
      String code,
      String questionnaireCanonical,
      String questionnaireName,
      String trn,
      Integer lineNumber,
      String status) {
  }
}
