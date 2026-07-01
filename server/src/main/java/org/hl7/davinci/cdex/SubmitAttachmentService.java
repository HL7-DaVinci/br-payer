package org.hl7.davinci.cdex;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.pas.PasPendedResolutionService;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
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

  public OperationOutcome submit(Identifier trackingId, Identifier adminRefNumber, CodeType attachTo,
      Identifier payerId, Identifier organizationId, Identifier providerId, Identifier memberId,
      DateTimeType serviceDate, List<ParametersParameterComponent> attachments, BooleanType isFinal) {

    validate(trackingId, attachTo, organizationId, providerId, memberId, serviceDate, attachments);

    List<Resource> contents = extractContents(attachments);
    storeAttachments(contents);

    ClaimResponse target = findClaimResponseByTrackingId(trackingId);
    if (target != null) {
      String crId = target.getIdElement().getIdPart();
      boolean resolved = resolutionService.resolveNow(crId);
      if (resolved) {
        log.info("$submit-attachment: associated {} attachment(s) with ClaimResponse/{} (TrackingId {})",
            contents.size(), crId, trackingId.getValue());
        return informational("Attachments accepted and associated with the prior authorization.");
      }
      // The prior authorization was already decided (e.g. resolved out of band). Record the attachment
      // without re-resolving, so a late or repeat submission is idempotent and never errors.
      log.info("$submit-attachment: recorded {} attachment(s) for already-decided ClaimResponse/{} "
          + "(TrackingId {})", contents.size(), crId, trackingId.getValue());
      return informational(
          "Attachments accepted and recorded. The prior authorization has already been decided.");
    }

    log.info("$submit-attachment: accepted {} attachment(s) with no matching claim or prior "
        + "authorization (TrackingId {}); held for future association",
        contents.size(), trackingId.getValue());
    return informational("Attachments accepted. No matching claim or prior authorization was found; "
        + "they are held for future association.");
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

  private List<Resource> extractContents(List<ParametersParameterComponent> attachments) {
    List<Resource> contents = new ArrayList<>();
    for (ParametersParameterComponent attachment : attachments) {
      Resource content = null;
      int contentCount = 0;
      for (ParametersParameterComponent part : attachment.getPart()) {
        if (CdexConstants.PARAM_ATTACHMENT_CONTENT.equals(part.getName()) && part.getResource() != null) {
          content = part.getResource();
          contentCount++;
        }
      }
      if (contentCount != 1) {
        throw new IllegalArgumentException("Each Attachment must contain exactly one Content resource.");
      }
      contents.add(content);
    }
    return contents;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void storeAttachments(List<Resource> contents) {
    for (Resource content : contents) {
      sanitizeReferences(content);
      IFhirResourceDao dao = daoRegistry.getResourceDao(content.fhirType());
      dao.create(content, new SystemRequestDetails());
    }
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

  private OperationOutcome informational(String message) {
    return OperationOutcomeBuilder.createOperationOutcome(
        IssueSeverity.INFORMATION, IssueType.INFORMATIONAL, null, message);
  }
}
