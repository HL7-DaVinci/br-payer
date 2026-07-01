package org.hl7.davinci.cdex;

import java.util.List;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters.ParametersParameterComponent;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

@Component
public class SubmitAttachmentProvider extends BaseProvider {

  private final SubmitAttachmentService service;

  public SubmitAttachmentProvider(SubmitAttachmentService service) {
    this.service = service;
  }

  @Operation(
      name = "$" + CdexConstants.OPERATION_SUBMIT_ATTACHMENT,
      idempotent = false,
      canonicalUrl = CdexConstants.OPERATION_SUBMIT_ATTACHMENT_URL)
  public OperationOutcome submitAttachment(
      @OperationParam(name = CdexConstants.PARAM_TRACKING_ID, min = 1, max = 1, type = Identifier.class) Identifier trackingId,
      @OperationParam(name = CdexConstants.PARAM_ADMIN_REF_NUMBER, min = 0, max = 1, type = Identifier.class) Identifier adminRefNumber,
      @OperationParam(name = CdexConstants.PARAM_ATTACH_TO, min = 1, max = 1, type = CodeType.class) CodeType attachTo,
      @OperationParam(name = CdexConstants.PARAM_PAYER_ID, min = 0, max = 1, type = Identifier.class) Identifier payerId,
      @OperationParam(name = CdexConstants.PARAM_ORGANIZATION_ID, min = 0, max = 1, type = Identifier.class) Identifier organizationId,
      @OperationParam(name = CdexConstants.PARAM_PROVIDER_ID, min = 0, max = 1, type = Identifier.class) Identifier providerId,
      @OperationParam(name = CdexConstants.PARAM_MEMBER_ID, min = 1, max = 1, type = Identifier.class) Identifier memberId,
      @OperationParam(name = CdexConstants.PARAM_SERVICE_DATE, min = 0, max = 1, type = DateTimeType.class) DateTimeType serviceDate,
      @OperationParam(name = CdexConstants.PARAM_ATTACHMENT, min = 1, max = OperationParam.MAX_UNLIMITED, type = ParametersParameterComponent.class) List<ParametersParameterComponent> attachments,
      @OperationParam(name = CdexConstants.PARAM_FINAL, min = 0, max = 1, type = BooleanType.class) BooleanType isFinal) {

    try {
      return service.submit(trackingId, adminRefNumber, attachTo, payerId, organizationId, providerId,
          memberId, serviceDate, attachments, isFinal);
    } catch (IllegalArgumentException e) {
      throw new InvalidRequestException(e.getMessage(),
          OperationOutcomeBuilder.createBadRequestOutcome(e.getMessage()));
    }
  }
}
