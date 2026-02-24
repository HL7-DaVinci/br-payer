package org.hl7.davinci.pas;

import org.hl7.fhir.r4.model.*;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Utility methods for building and extracting PAS IG FHIR extensions.
 * Constants (extension URLs, code values, profile URLs) live in {@link PasConstants}.
 */
public final class PasExtensions {

  private PasExtensions() {
  }

  /**
   * Builds a reviewAction extension with the given X12 306 code and optional auth
   * number.
   */
  public static Extension buildReviewActionExtension(String reviewCode, String displayText, String authNumber) {
    Extension reviewAction = new Extension(PasConstants.REVIEW_ACTION);

    Extension codeExt = new Extension(PasConstants.REVIEW_ACTION_CODE);
    codeExt.setValue(new CodeableConcept().addCoding(
        new Coding(PasConstants.X12_REVIEW_CODE_SYSTEM, reviewCode, displayText)));
    reviewAction.addExtension(codeExt);

    if (authNumber != null) {
      reviewAction.addExtension("number", new StringType(authNumber));
    }

    return reviewAction;
  }

  /**
   * Extracts the X12 306 review action code from a ClaimResponse item's adjudication.
   * Walks the item's adjudications looking for the reviewAction extension and returns
   * the first review action code found, or null if none present.
   */
  public static String extractReviewActionCode(ClaimResponse.ItemComponent item) {
    for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
      Extension reviewAction = adj.getExtensionByUrl(PasConstants.REVIEW_ACTION);
      if (reviewAction == null) continue;

      Extension codeExt = reviewAction.getExtensionByUrl(PasConstants.REVIEW_ACTION_CODE);
      if (codeExt == null || !(codeExt.getValue() instanceof CodeableConcept cc)) continue;

      Coding coding = cc.getCodingFirstRep();
      if (coding != null && coding.hasCode()) {
        return coding.getCode();
      }
    }
    return null;
  }

  /**
   * Extracts the authorization number from a ClaimResponse item's adjudication reviewAction.
   * Walks adjudications looking for the reviewAction extension's "number" sub-extension.
   */
  public static String extractAuthorizationNumber(ClaimResponse.ItemComponent item) {
    for (ClaimResponse.AdjudicationComponent adj : item.getAdjudication()) {
      Extension reviewAction = adj.getExtensionByUrl(PasConstants.REVIEW_ACTION);
      if (reviewAction == null) continue;

      Extension numberExt = reviewAction.getExtensionByUrl("number");
      if (numberExt != null && numberExt.getValue() instanceof StringType st && st.hasValue()) {
        return st.getValue();
      }
    }
    return null;
  }

  /**
   * Extracts the administrationReferenceNumber from a ClaimResponse item extension.
   */
  public static String extractAdminRefNumber(ClaimResponse.ItemComponent item) {
    Extension ext = item.getExtensionByUrl(PasConstants.ADMIN_REF_NUMBER);
    if (ext != null && ext.getValue() instanceof StringType st && st.hasValue()) {
      return st.getValue();
    }
    return null;
  }

  /**
   * Collects all authorization numbers across all items of a ClaimResponse.
   */
  public static Set<String> extractAllAuthorizationNumbers(ClaimResponse cr) {
    Set<String> result = new LinkedHashSet<>();
    for (ClaimResponse.ItemComponent item : cr.getItem()) {
      String authNum = extractAuthorizationNumber(item);
      if (authNum != null) {
        result.add(authNum);
      }
    }
    return result;
  }

  /**
   * Collects all administrationReferenceNumbers across all items of a ClaimResponse.
   */
  public static Set<String> extractAllAdminRefNumbers(ClaimResponse cr) {
    Set<String> result = new LinkedHashSet<>();
    for (ClaimResponse.ItemComponent item : cr.getItem()) {
      String adminRef = extractAdminRefNumber(item);
      if (adminRef != null) {
        result.add(adminRef);
      }
    }
    return result;
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
    return new Extension(PasConstants.ITEM_PREAUTH_PERIOD, period);
  }
}
