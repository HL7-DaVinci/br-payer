package org.hl7.davinci.pas;

import static org.hl7.davinci.common.FhirConstants.X12_REVIEW_CODE_SYSTEM;

import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Type;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
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
        new Coding(X12_REVIEW_CODE_SYSTEM, reviewCode, displayText)));
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
   * Builds an itemTraceNumber extension wrapping the given Identifier.
   */
  public static Extension buildItemTraceNumberExtension(Identifier traceId) {
    return new Extension(PasConstants.ITEM_TRACE_NUMBER, traceId.copy());
  }

  /**
   * Builds an itemPreAuthIssueDate extension with the given date.
   */
  public static Extension buildPreAuthIssueDateExtension(Date issueDate) {
    return new Extension(PasConstants.ITEM_PREAUTH_ISSUE_DATE, new DateType(issueDate));
  }

  /**
   * Builds an itemRequestedServiceDate extension wrapping the given serviced[x] value.
   */
  public static Extension buildRequestedServiceDateExtension(Type servicedValue) {
    return new Extension(PasConstants.ITEM_REQUESTED_SERVICE_DATE, servicedValue.copy());
  }

  /**
   * Builds a transmissionIdentifiers extension
   * Requires two sub-extensions: applicationSenderCode and applicationReceiverCode.
   * The system/value pair is used to set the sender code; receiver code uses the same system.
   */
  public static Extension buildTransmissionIdentifiersExtension(String senderCode, String receiverCode) {
    Extension ext = new Extension(PasConstants.TRANSMISSION_IDENTIFIERS);
    ext.addExtension("applicationSenderCode", new StringType(senderCode));
    ext.addExtension("applicationReceiverCode", new StringType(receiverCode));
    return ext;
  }

  /**
   * Extracts the full TransmissionIdentifiers extension from a resource, or null if absent.
   */
  public static Extension extractTransmissionIdentifiers(DomainResource resource) {
    return resource.getExtensionByUrl(PasConstants.TRANSMISSION_IDENTIFIERS);
  }

  /**
   * Extracts the applicationSenderCode value from a resource's TransmissionIdentifiers
   * extension, or null if the extension or sub-extension is absent.
   */
  public static String extractApplicationSenderCode(DomainResource resource) {
    return extractTransmissionIdentifierValue(resource, "applicationSenderCode");
  }

  /**
   * Extracts the applicationReceiverCode value from a resource's TransmissionIdentifiers
   * extension, or null if the extension or sub-extension is absent.
   */
  public static String extractApplicationReceiverCode(DomainResource resource) {
    return extractTransmissionIdentifierValue(resource, "applicationReceiverCode");
  }

  private static String extractTransmissionIdentifierValue(DomainResource resource,
      String subExtensionUrl) {
    Extension transmissionIds = extractTransmissionIdentifiers(resource);
    if (transmissionIds == null) {
      return null;
    }

    Extension valueExtension = transmissionIds.getExtensionByUrl(subExtensionUrl);
    if (valueExtension != null && valueExtension.getValue() instanceof StringType stringType
        && stringType.hasValue()) {
      return stringType.getValue();
    }

    return null;
  }

  /**
   * Extracts itemTraceNumber Identifiers from a Claim item's extensions.
   */
  public static List<Identifier> extractItemTraceNumbers(Claim.ItemComponent item) {
    List<Identifier> result = new ArrayList<>();
    for (Extension ext : item.getExtensionsByUrl(PasConstants.ITEM_TRACE_NUMBER)) {
      if (ext.getValue() instanceof Identifier id) {
        result.add(id);
      }
    }
    return result;
  }

  /**
   * Extracts the serviced[x] value (Period or DateType) from a Claim item, or null if absent.
   */
  public static Type extractServicedValue(Claim.ItemComponent item) {
    if (item.hasServiced()) {
      return item.getServiced();
    }
    return null;
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

  /**
   * Builds a PAS Task resource requesting additional documentation via DTR questionnaires.
   * Per the PAS IG, Task.code = "attachment-request-questionnaire" directs providers to
   * complete the referenced questionnaires using DTR.
   *
   * @param claimReference reference to the originating Claim (e.g. "Claim/123")
   * @param patientReference reference to the patient (e.g. "Patient/456")
   * @param insurerReference reference to the insurer organization
   * @param questionnaireUrls canonical URLs for DTR questionnaires
   * @param payerFhirUrl the payer's FHIR endpoint for $submit-attachment
   */
  public static Task buildDocumentationRequestTask(
      String claimReference,
      String patientReference,
      String insurerReference,
      List<String> questionnaireUrls,
      String payerFhirUrl) {
    Task task = new Task();
    task.setId("task-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    task.setMeta(new Meta().addProfile(PasConstants.PROFILE_PAS_TASK));
    task.setStatus(Task.TaskStatus.REQUESTED);
    task.setIntent(Task.TaskIntent.ORDER);
    task.setCode(new CodeableConcept().addCoding(new Coding(
        PasConstants.TASK_CODE_SYSTEM,
        PasConstants.TASK_CODE_QUESTIONNAIRE_REQUEST,
        "Questionnaire Attachment Request")));

    task.setFor(new Reference(patientReference));
    task.setRequester(new Reference(insurerReference));
    task.setReasonReference(new Reference(claimReference));

    // payerUrl input -- where to submit completed documentation
    if (payerFhirUrl != null) {
      task.addInput()
          .setType(new CodeableConcept().addCoding(new Coding(
              PasConstants.TASK_CODE_SYSTEM, "payer-url", "Payer URL")))
          .setValue(new StringType(payerFhirUrl));
    }

    // questionnairesNeeded inputs -- canonical URLs for DTR
    for (String url : questionnaireUrls) {
      task.addInput()
          .setType(new CodeableConcept().addCoding(new Coding(
              PasConstants.TASK_CODE_SYSTEM, "questionnaires-needed", "Questionnaires Needed")))
          .setValue(new CanonicalType(url));
    }

    return task;
  }
}
