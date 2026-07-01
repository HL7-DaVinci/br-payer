package org.hl7.davinci.pas;

/**
 * PAS IG extension URLs, review action codes, and profile URL constants.
 */
public final class PasConstants {

  private PasConstants() {
  }

  // Base URL for PAS extensions
  public static final String PAS_BASE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/";

  // ClaimResponse extensions
  public static final String REVIEW_ACTION = PAS_BASE + "extension-reviewAction";
  public static final String REVIEW_ACTION_CODE = PAS_BASE + "extension-reviewActionCode";
  public static final String AUTHORIZATION_NUMBER = PAS_BASE + "extension-authorizationNumber";
  public static final String ADMIN_REF_NUMBER = PAS_BASE + "extension-administrationReferenceNumber";

  // ClaimResponse.item extensions
  public static final String ITEM_REQUESTED_SERVICE_DATE = PAS_BASE + "extension-itemRequestedServiceDate";
  public static final String ITEM_PREAUTH_ISSUE_DATE = PAS_BASE + "extension-itemPreAuthIssueDate";
  public static final String ITEM_PREAUTH_PERIOD = PAS_BASE + "extension-itemPreAuthPeriod";
  public static final String ITEM_AUTHORIZED_PROVIDER = PAS_BASE + "extension-itemAuthorizedProvider";
  public static final String ITEM_AUTHORIZED_DETAIL = PAS_BASE + "extension-itemAuthorizedDetail";
  public static final String ITEM_TRACE_NUMBER = PAS_BASE + "extension-itemTraceNumber";

  // Claim extensions
  public static final String CARE_TEAM_CLAIM_SCOPE = PAS_BASE + "extension-careTeamClaimScope";
  public static final String CERTIFICATION_TYPE = PAS_BASE + "extension-certificationType";
  public static final String SERVICE_ITEM_REQUEST_TYPE = PAS_BASE + "extension-serviceItemRequestType";
  public static final String LEVEL_OF_SERVICE_CODE = PAS_BASE + "extension-levelOfServiceCode";
  public static final String TRANSMISSION_IDENTIFIERS = PAS_BASE + "extension-TransmissionIdentifiers";
  public static final String ITEM_REQUESTED_SERVICE = PAS_BASE + "extension-requestedService";

  // PAS Task codes for requesting additional documentation
  public static final String TASK_CODE_SYSTEM = "http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes";
  public static final String TASK_CODE_ATTACHMENT_REQUEST = "attachment-request-code";
  public static final String TASK_CODE_QUESTIONNAIRE_REQUEST = "attachment-request-questionnaire";
  public static final String PROFILE_PAS_TASK = PAS_BASE + "profile-task";

  // Claim Update extensions
  public static final String INFO_CHANGED = PAS_BASE + "extension-infoChanged";
  public static final String INFO_CANCELLED = PAS_BASE + "modifierextension-infoCancelled";

  // Subscription Topic
  public static final String PAS_SUBSCRIPTION_TOPIC =
      "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic";
  public static final String PROFILE_PAS_SUBSCRIPTION =
      "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-subscription";

  // Subscription Backport extensions
  public static final String BACKPORT_FILTER_CRITERIA =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria";
  public static final String BACKPORT_PAYLOAD_CONTENT =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-payload-content";

  // HAPI topic subscription meta tag
  public static final String SUBSCRIPTION_MATCHING_TAG_SYSTEM =
      "http://hapifhir.io/fhir/StructureDefinition/subscription-matching-strategy";
  public static final String SUBSCRIPTION_MATCHING_TAG_CODE = "TOPIC";

  // Filter parameter
  public static final String FILTER_ORG_IDENTIFIER = "orgIdentifier";

  // PAS Profile URLs
  public static final String PROFILE_PAS_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle";
  public static final String PROFILE_PAS_INQUIRY_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-request-bundle";
  public static final String PROFILE_PAS_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-response-bundle";
  public static final String PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-response-bundle";
  public static final String PROFILE_PAS_CLAIM = PAS_BASE + "profile-claim";
  public static final String PROFILE_PAS_CLAIM_INQUIRY = PAS_BASE + "profile-claim-inquiry";
  public static final String PROFILE_PAS_CLAIM_RESPONSE = PAS_BASE + "profile-claimresponse";
  public static final String PROFILE_PAS_CLAIM_UPDATE = PAS_BASE + "profile-claim-update";
  public static final String PROFILE_PAS_BENEFICIARY = PAS_BASE + "profile-beneficiary";
  public static final String PROFILE_PAS_SUBSCRIBER = PAS_BASE + "profile-subscriber";
  public static final String PROFILE_PAS_INSURER = PAS_BASE + "profile-insurer";
  public static final String PROFILE_PAS_REQUESTOR = PAS_BASE + "profile-requestor";
  public static final String PROFILE_PAS_COVERAGE = PAS_BASE + "profile-coverage";
  public static final String PROFILE_PAS_PRACTITIONER_ROLE = PAS_BASE + "profile-practitionerrole";
  public static final String PROFILE_PAS_PRACTITIONER = PAS_BASE + "profile-practitioner";
  public static final String PROFILE_PAS_SERVICE_REQUEST = PAS_BASE + "profile-servicerequest";
  public static final String PROFILE_PAS_COMMUNICATION_REQUEST = PAS_BASE + "profile-communicationrequest";

  public static final String EXT_SERVICE_LINE_NUMBER = PAS_BASE + "extension-serviceLineNumber";
  public static final String EXT_CONTENT_MODIFIER = PAS_BASE + "extension-contentModifier";
  public static final String EXT_COMMUNICATED_DIAGNOSIS = PAS_BASE + "extension-communicatedDiagnosis";

  public static final String X12_REQUEST_CATEGORY_SYSTEM = "https://codesystem.x12.org/005010/755";

  // Additional-information request codes (CommunicationRequest.payload.content required binding)
  public static final String LOINC_SYSTEM = "http://loinc.org";
  public static final String LOINC_QUESTIONNAIRE_REQUEST = "102089-0";

  // Item trace number namespace identifying the DTR context for a questionnaire request. PAS
  // PASIdentifier recommends a scheme of urn:trnorg:<TRN03>; the value carried is the DTR context id
  // (the requested questionnaire's logical id).
  public static final String QUESTIONNAIRE_TRACE_NUMBER_SYSTEM = "urn:trnorg:PASPAYER";

}
