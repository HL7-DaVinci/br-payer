package org.hl7.davinci.pas;

import org.hl7.fhir.r4.model.*;
import java.util.Date;

/**
 * PAS IG extension URLs and review action code constants.
 */
public final class PasExtensions {

  private PasExtensions() {
  }

  // Base URL for PAS extensions
  public static final String PAS_EXT_BASE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/";

  // ClaimResponse extensions
  public static final String REVIEW_ACTION = PAS_EXT_BASE + "extension-reviewAction";
  public static final String REVIEW_ACTION_CODE = PAS_EXT_BASE + "extension-reviewActionCode";
  public static final String AUTHORIZATION_NUMBER = PAS_EXT_BASE + "extension-authorizationNumber";
  public static final String ADMIN_REF_NUMBER = PAS_EXT_BASE + "extension-administrationReferenceNumber";

  // ClaimResponse.item extensions
  public static final String ITEM_REQUESTED_SERVICE_DATE = PAS_EXT_BASE + "extension-itemRequestedServiceDate";
  public static final String ITEM_PREAUTH_ISSUE_DATE = PAS_EXT_BASE + "extension-itemPreAuthIssueDate";
  public static final String ITEM_PREAUTH_PERIOD = PAS_EXT_BASE + "extension-itemPreAuthPeriod";
  public static final String ITEM_AUTHORIZED_PROVIDER = PAS_EXT_BASE + "extension-itemAuthorizedProvider";
  public static final String ITEM_AUTHORIZED_DETAIL = PAS_EXT_BASE + "extension-itemAuthorizedDetail";
  public static final String ITEM_TRACE_NUMBER = PAS_EXT_BASE + "extension-itemTraceNumber";

  // Claim extensions
  public static final String CERTIFICATION_TYPE = PAS_EXT_BASE + "extension-certificationType";
  public static final String SERVICE_ITEM_REQUEST_TYPE = PAS_EXT_BASE + "extension-serviceItemRequestType";
  public static final String LEVEL_OF_SERVICE_CODE = PAS_EXT_BASE + "extension-levelOfServiceCode";
  public static final String TRANSMISSION_IDENTIFIERS = PAS_EXT_BASE + "extension-TransmissionIdentifiers";

  // Claim Update extensions
  public static final String INFO_CHANGED = PAS_EXT_BASE + "extension-infoChanged";
  public static final String INFO_CANCELLED = PAS_EXT_BASE + "modifierextension-infoCancelled";

  // X12 306 Review Action Codes (https://codesystem.x12.org/005010/306)
  public static final String X12_REVIEW_CODE_SYSTEM = "https://codesystem.x12.org/005010/306";
  public static final String REVIEW_CODE_A1 = "A1"; // Certified in Total
  public static final String REVIEW_CODE_A2 = "A2"; // Not Certified
  public static final String REVIEW_CODE_A3 = "A3"; // Not Required
  public static final String REVIEW_CODE_A4 = "A4"; // Pended
  public static final String REVIEW_CODE_A6 = "A6"; // Modified

  // PAS Profile URLs
  public static final String PROFILE_PAS_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle";
  public static final String PROFILE_PAS_INQUIRY_REQUEST_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-request-bundle";
  public static final String PROFILE_PAS_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-response-bundle";
  public static final String PROFILE_PAS_INQUIRY_RESPONSE_BUNDLE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-inquiry-response-bundle";
  public static final String PROFILE_PAS_CLAIM = PAS_EXT_BASE + "profile-claim";
  public static final String PROFILE_PAS_CLAIM_INQUIRY = PAS_EXT_BASE + "profile-claim-inquiry";
  public static final String PROFILE_PAS_CLAIM_RESPONSE = PAS_EXT_BASE + "profile-claimresponse";
  public static final String PROFILE_PAS_CLAIM_UPDATE = PAS_EXT_BASE + "profile-claim-update";
  public static final String PROFILE_PAS_SUBSCRIBER = PAS_EXT_BASE + "profile-subscriber";
  public static final String PROFILE_PAS_INSURER = PAS_EXT_BASE + "profile-insurer";
  public static final String PROFILE_PAS_REQUESTOR = PAS_EXT_BASE + "profile-requestor";
  public static final String PROFILE_PAS_COVERAGE = PAS_EXT_BASE + "profile-coverage";
  public static final String PROFILE_PAS_PRACTITIONER_ROLE = PAS_EXT_BASE + "profile-practitionerrole";
  public static final String PROFILE_PAS_PRACTITIONER = PAS_EXT_BASE + "profile-practitioner";
  public static final String PROFILE_PAS_SERVICE_REQUEST = PAS_EXT_BASE + "profile-servicerequest";

  /**
   * Builds a reviewAction extension with the given X12 306 code and optional auth
   * number.
   */
  public static Extension buildReviewActionExtension(String reviewCode, String displayText, String authNumber) {
    Extension reviewAction = new Extension(REVIEW_ACTION);

    Extension codeExt = new Extension(REVIEW_ACTION_CODE);
    codeExt.setValue(new CodeableConcept().addCoding(
        new Coding(X12_REVIEW_CODE_SYSTEM, reviewCode, displayText)));
    reviewAction.addExtension(codeExt);

    if (authNumber != null) {
      reviewAction.addExtension("number", new StringType(authNumber));
    }

    return reviewAction;
  }

  /**
   * Builds an itemPreAuthPeriod extension for the given start/end dates.
   */
  public static Extension buildPreAuthPeriodExtension(Date start, Date end) {
    Period period = new Period();
    if (start != null)
      period.setStart(start);
    if (end != null)
      period.setEnd(end);
    return new Extension(ITEM_PREAUTH_PERIOD, period);
  }
}
