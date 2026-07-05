package org.hl7.davinci.cdex;

/** Da Vinci CDex 2.1.0 constants. */
public final class CdexConstants {

  private CdexConstants() {
  }


  public static final String OPERATION_SUBMIT_ATTACHMENT = "submit-attachment";
  public static final String OPERATION_SUBMIT_ATTACHMENT_URL =
      "http://hl7.org/fhir/us/davinci-cdex/OperationDefinition/submit-attachment";

  public static final String CDEX_CLAIM_USE_VALUE_SET =
      "http://hl7.org/fhir/us/davinci-cdex/ValueSet/cdex-claim-use";
  public static final String CLAIM_USE_CLAIM = "claim";
  public static final String CLAIM_USE_PREAUTHORIZATION = "preauthorization";

  public static final String PARAM_TRACKING_ID = "TrackingId";
  public static final String PARAM_ATTACH_TO = "AttachTo";
  public static final String PARAM_PAYER_ID = "PayerId";
  public static final String PARAM_ORGANIZATION_ID = "OrganizationId";
  public static final String PARAM_PROVIDER_ID = "ProviderId";
  public static final String PARAM_MEMBER_ID = "MemberId";
  public static final String PARAM_SERVICE_DATE = "ServiceDate";
  public static final String PARAM_ATTACHMENT = "Attachment";
  public static final String PARAM_ATTACHMENT_LINE_ITEM = "LineItem";
  public static final String PARAM_ATTACHMENT_CODE = "Code";
  public static final String PARAM_ATTACHMENT_CONTENT = "Content";
  public static final String PARAM_FINAL = "Final";
}
