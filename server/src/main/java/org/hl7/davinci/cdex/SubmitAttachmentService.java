package org.hl7.davinci.cdex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.pas.PasConstants;
import org.hl7.davinci.pas.PasPendedResolutionService;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Communication;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.CommunicationRequest.CommunicationRequestStatus;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.PrimitiveType;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.util.ResourceReferenceInfo;

@Service
public class SubmitAttachmentService {

  private static final Logger log = LoggerFactory.getLogger(SubmitAttachmentService.class);

  private final DaoRegistry daoRegistry;
  private final PasPendedResolutionService resolutionService;
  private final FhirContext fhirContext;

  public SubmitAttachmentService(DaoRegistry daoRegistry,
      PasPendedResolutionService resolutionService, FhirContext fhirContext) {
    this.daoRegistry = daoRegistry;
    this.resolutionService = resolutionService;
    this.fhirContext = fhirContext;
  }

  /** A submitted attachment: its content plus the service line numbers it targets (may be empty). */
  private record AttachmentContent(Resource content, Set<Integer> lineNumbers) {
  }

  public OperationOutcome submit(Identifier trackingId, CodeType attachTo,
      Identifier payerId, Identifier organizationId, Identifier providerId, Identifier memberId,
      DateTimeType serviceDate, List<ParametersParameterComponent> attachments, BooleanType isFinal) {

    validate(trackingId, attachTo, organizationId, providerId, memberId, serviceDate, attachments);

    List<AttachmentContent> contents = extractAttachments(attachments);
    ClaimResponse target = findClaimResponseByTrackingId(trackingId);
    List<CommunicationRequest> documentationRequests =
        target == null ? List.of() : loadDocumentationRequests(target);
    List<OperationOutcomeIssueComponent> extraIssues = new ArrayList<>();

    for (AttachmentContent attachment : contents) {
      storeAttachment(attachment.content(), trackingId, target);
      if (target != null) {
        completeSatisfiedRequests(target, documentationRequests, attachment, extraIssues);
      }
    }

    if (target == null) {
      log.info("$submit-attachment: accepted {} attachment(s) with no matching claim or prior "
          + "authorization (TrackingId {}); held for future association",
          contents.size(), trackingId.getValue());
      return withIssues(informational("Attachments accepted. No matching claim or prior authorization "
          + "was found; they are held for future association."), extraIssues);
    }

    String crId = target.getIdElement().getIdPart();
    boolean allRequestsCompleted = documentationRequests.stream()
        .allMatch(cr -> cr.getStatus() == CommunicationRequestStatus.COMPLETED);
    boolean finalOrAbsent = isFinal == null || isFinal.booleanValue();

    if (allRequestsCompleted && finalOrAbsent) {
      boolean resolved = resolutionService.resolveNow(crId);
      if (resolved) {
        log.info("$submit-attachment: associated {} attachment(s) with ClaimResponse/{} (TrackingId {})",
            contents.size(), crId, trackingId.getValue());
        return withIssues(
            informational("Attachments accepted and associated with the prior authorization."),
            extraIssues);
      }
      // The prior authorization was already decided (e.g. resolved out of band). Record the attachment
      // without re-resolving, so a late or repeat submission is idempotent and never errors.
      log.info("$submit-attachment: recorded {} attachment(s) for already-decided ClaimResponse/{} "
          + "(TrackingId {})", contents.size(), crId, trackingId.getValue());
      return withIssues(informational(
          "Attachments accepted and recorded. The prior authorization has already been decided."),
          extraIssues);
    }

    log.info("$submit-attachment: recorded {} attachment(s) for ClaimResponse/{} (TrackingId {}); "
        + "awaiting remaining requested documentation", contents.size(), crId, trackingId.getValue());
    return withIssues(informational("Attachments accepted and recorded. Awaiting the remaining "
        + "requested documentation before the prior authorization can be finalized."), extraIssues);
  }

  private void validate(Identifier trackingId, CodeType attachTo, Identifier organizationId,
      Identifier providerId, Identifier memberId, DateTimeType serviceDate,
      List<ParametersParameterComponent> attachments) {

    if (attachments == null || attachments.isEmpty()) {
      throw new IllegalArgumentException("At least one Attachment is required.");
    }
    if (trackingId == null || !trackingId.hasValue()) {
      throw new IllegalArgumentException("TrackingId is required.");
    }
    String attachToCode = attachTo == null ? null : attachTo.getValue();
    if (!CdexConstants.CLAIM_USE_CLAIM.equals(attachToCode)
        && !CdexConstants.CLAIM_USE_PREAUTHORIZATION.equals(attachToCode)) {
      throw new IllegalArgumentException("AttachTo must be '" + CdexConstants.CLAIM_USE_CLAIM
          + "' or '" + CdexConstants.CLAIM_USE_PREAUTHORIZATION + "'.");
    }
    if (memberId == null || !memberId.hasValue()) {
      throw new IllegalArgumentException("MemberId is required.");
    }
    boolean hasOrg = organizationId != null && organizationId.hasValue();
    boolean hasProvider = providerId != null && providerId.hasValue();
    if (!hasOrg && !hasProvider) {
      throw new IllegalArgumentException("Either OrganizationId or ProviderId (or both) is required.");
    }
    if (CdexConstants.CLAIM_USE_CLAIM.equals(attachToCode) && (serviceDate == null || !serviceDate.hasValue())) {
      throw new IllegalArgumentException("ServiceDate is required when AttachTo is 'claim'.");
    }
  }

  private List<AttachmentContent> extractAttachments(List<ParametersParameterComponent> attachments) {
    List<AttachmentContent> result = new ArrayList<>();
    for (ParametersParameterComponent attachment : attachments) {
      Resource content = null;
      int contentCount = 0;
      Set<Integer> lineNumbers = new HashSet<>();
      for (ParametersParameterComponent part : attachment.getPart()) {
        if (CdexConstants.PARAM_ATTACHMENT_CONTENT.equals(part.getName()) && part.getResource() != null) {
          content = part.getResource();
          contentCount++;
        } else if (CdexConstants.PARAM_ATTACHMENT_LINE_ITEM.equals(part.getName())
            && part.getValue() instanceof PrimitiveType<?> value && value.hasPrimitiveValue()) {
          try {
            lineNumbers.add(Integer.parseInt(value.getValueAsString().trim()));
          } catch (NumberFormatException e) {
            log.warn("$submit-attachment: ignoring non-numeric LineItem '{}'", value.getValueAsString());
          }
        }
      }
      if (contentCount != 1) {
        throw new IllegalArgumentException("Each Attachment must contain exactly one Content resource.");
      }
      result.add(new AttachmentContent(content, lineNumbers));
    }
    return result;
  }

  /**
   * Marks the documentation requests satisfied by one attachment as completed. LineItem parts scope
   * to specific service lines; a QuestionnaireResponse is further matched to the request whose
   * TRN-linked questionnaire equals the submitted {@code questionnaire} canonical. A canonical that
   * matches no requested questionnaire is stored but records an informational mismatch issue.
   */
  private void completeSatisfiedRequests(ClaimResponse target,
      List<CommunicationRequest> documentationRequests, AttachmentContent attachment,
      List<OperationOutcomeIssueComponent> extraIssues) {

    List<CommunicationRequest> candidates = new ArrayList<>();
    for (CommunicationRequest cr : documentationRequests) {
      if (cr.getStatus() != CommunicationRequestStatus.COMPLETED) {
        candidates.add(cr);
      }
    }

    if (!attachment.lineNumbers().isEmpty()) {
      candidates.removeIf(cr -> !attachment.lineNumbers().contains(serviceLineNumber(cr)));
    }

    if (attachment.content() instanceof QuestionnaireResponse qr && qr.hasQuestionnaire()) {
      String canonical = qr.getQuestionnaire();
      String questionnaireId = resolveQuestionnaireLogicalId(canonical);
      List<CommunicationRequest> matching = new ArrayList<>();
      for (CommunicationRequest cr : candidates) {
        if (Objects.equals(traceNumber(cr), questionnaireId)) {
          matching.add(cr);
        }
      }
      if (matching.isEmpty()) {
        log.warn("$submit-attachment: submitted QuestionnaireResponse questionnaire '{}' does not "
            + "match any requested questionnaire for ClaimResponse/{}; stored without completing a "
            + "documentation request", canonical, target.getIdElement().getIdPart());
        extraIssues.add(new OperationOutcomeIssueComponent()
            .setSeverity(IssueSeverity.INFORMATION)
            .setCode(IssueType.INFORMATIONAL)
            .setDiagnostics("Submitted QuestionnaireResponse references questionnaire '" + canonical
                + "' which does not match any requested questionnaire for this prior authorization; "
                + "the attachment was stored but did not satisfy a documentation request."));
        return;
      }
      candidates = matching;
    }

    for (CommunicationRequest cr : candidates) {
      cr.setStatus(CommunicationRequestStatus.COMPLETED);
      daoRegistry.getResourceDao(CommunicationRequest.class).update(cr, new SystemRequestDetails());
    }
  }

  private List<CommunicationRequest> loadDocumentationRequests(ClaimResponse target) {
    List<CommunicationRequest> requests = new ArrayList<>();
    IFhirResourceDao<CommunicationRequest> dao = daoRegistry.getResourceDao(CommunicationRequest.class);
    for (Reference ref : target.getCommunicationRequest()) {
      if (!ref.hasReference()) {
        continue;
      }
      try {
        CommunicationRequest cr = dao.read(new IdType(ref.getReference()), new SystemRequestDetails());
        if (cr != null) {
          requests.add(cr);
        }
      } catch (RuntimeException e) {
        log.debug("CommunicationRequest {} could not be read, skipping", ref.getReference());
      }
    }
    return requests;
  }

  private Integer serviceLineNumber(CommunicationRequest cr) {
    Extension ext = cr.getExtensionByUrl(PasConstants.EXT_SERVICE_LINE_NUMBER);
    if (ext == null || !(ext.getValue() instanceof PrimitiveType<?> value) || !value.hasPrimitiveValue()) {
      return null;
    }
    try {
      return Integer.parseInt(value.getValueAsString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String traceNumber(CommunicationRequest cr) {
    for (Identifier id : cr.getIdentifier()) {
      if (PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM.equals(id.getSystem())) {
        return id.getValue();
      }
    }
    return cr.hasIdentifier() ? cr.getIdentifierFirstRep().getValue() : null;
  }

  private String resolveQuestionnaireLogicalId(String canonical) {
    Questionnaire questionnaire = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
    return questionnaire == null ? null : questionnaire.getIdElement().getIdPart();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void storeAttachment(Resource content, Identifier trackingId, ClaimResponse target) {
    boolean stamped = stampTrackingId(content, trackingId);
    sanitizeReferences(content);
    IFhirResourceDao dao = daoRegistry.getResourceDao(content.fhirType());
    DaoMethodOutcome outcome = dao.create(content, new SystemRequestDetails());
    if (!stamped) {
      createLinkingCommunication(outcome, trackingId, target);
    }
  }

  /**
   * Stamps the TrackingId onto identifier-bearing content so the association survives storage.
   * Returns false for content that has no identifier element, signaling a linking Communication is needed.
   */
  private boolean stampTrackingId(Resource content, Identifier trackingId) {
    BaseRuntimeChildDefinition identifierChild =
        fhirContext.getResourceDefinition(content).getChildByName("identifier");
    if (identifierChild == null) {
      return false;
    }
    if (identifierChild.getMax() == 1) {
      identifierChild.getMutator().setValue(content, trackingId.copy());
    } else {
      identifierChild.getMutator().addValue(content, trackingId.copy());
    }
    return true;
  }

  private void createLinkingCommunication(DaoMethodOutcome storedOutcome, Identifier trackingId,
      ClaimResponse target) {
    Communication communication = new Communication();
    communication.addIdentifier(trackingId.copy());
    communication.setStatus(Communication.CommunicationStatus.COMPLETED);
    if (target != null) {
      communication.addAbout(new Reference("ClaimResponse/" + target.getIdElement().getIdPart()));
    }
    if (storedOutcome != null && storedOutcome.getId() != null) {
      communication.addPayload().setContent(
          new Reference(storedOutcome.getId().toUnqualifiedVersionless().getValue()));
    }
    daoRegistry.getResourceDao(Communication.class).create(communication, new SystemRequestDetails());
  }

  /**
   * Clears literal references that do not resolve on this server. Attachment content authored by
   * the submitter references the submitter's local resources (e.g. the ordering ServiceRequest),
   * which must not block storing received documentation via referential integrity. References that
   * do resolve here (e.g. the member Patient) are kept.
   */
  private void sanitizeReferences(Resource content) {
    for (ResourceReferenceInfo info : fhirContext.newTerser().getAllResourceReferences(content)) {
      IBaseReference ref = info.getResourceReference();
      IIdType id = ref.getReferenceElement();
      if (id != null && id.hasResourceType() && id.hasIdPart() && !resourceExists(id)) {
        ref.setReference(null);
      }
    }
  }

  private boolean resourceExists(IIdType id) {
    try {
      daoRegistry.getResourceDao(id.getResourceType())
          .read(id.toUnqualifiedVersionless(), new SystemRequestDetails());
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private ClaimResponse findClaimResponseByTrackingId(Identifier trackingId) {
    SearchParameterMap params = new SearchParameterMap();
    TokenParam token = trackingId.hasSystem()
        ? new TokenParam(trackingId.getSystem(), trackingId.getValue())
        : new TokenParam(trackingId.getValue());
    params.add("identifier", token);
    params.setLoadSynchronous(true);

    List<ClaimResponse> results = daoRegistry.getResourceDao(ClaimResponse.class)
        .searchForResources(params, new SystemRequestDetails());
    return results.isEmpty() ? null : results.get(0);
  }

  private OperationOutcome withIssues(OperationOutcome outcome,
      List<OperationOutcomeIssueComponent> extraIssues) {
    for (OperationOutcomeIssueComponent issue : extraIssues) {
      outcome.addIssue(issue);
    }
    return outcome;
  }

  private OperationOutcome informational(String message) {
    return OperationOutcomeBuilder.createOperationOutcome(
        IssueSeverity.INFORMATION, IssueType.INFORMATIONAL, null, message);
  }
}
