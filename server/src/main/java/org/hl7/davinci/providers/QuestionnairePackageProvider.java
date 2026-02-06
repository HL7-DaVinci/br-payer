package org.hl7.davinci.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;

/**
 * Implements the DTR $questionnaire-package operation.
 *
 * @see <a href="http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/questionnaire-package">
 *   DTR OperationDefinition</a>
 */
@Component
public class QuestionnairePackageProvider extends BaseProvider {

  private static final Logger logger = LoggerFactory.getLogger(QuestionnairePackageProvider.class);

  /**
   * DTR-allowed order resource types per OperationDefinition.
   */
  private static final Set<String> SUPPORTED_ORDER_TYPES = Set.of(
      "Appointment",
      "CommunicationRequest",
      "DeviceRequest",
      "Encounter",
      "MedicationRequest",
      "NutritionOrder",
      "ServiceRequest",
      "SupplyRequest",
      "VisionPrescription");

  @Operation(
      name = "$questionnaire-package",
      type = Questionnaire.class,
      canonicalUrl = "http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/questionnaire-package")
  public Parameters questionnairePackage(
      @OperationParam(name = "coverage", min = 1, max = 1, type = Coverage.class) Coverage theCoverage,
      @OperationParam(name = "order", min = 0, type = IAnyResource.class) List<IAnyResource> theOrders,
      @OperationParam(name = "questionnaire", min = 0, type = CanonicalType.class) List<CanonicalType> theQuestionnaires,
      @OperationParam(name = "context", min = 0, max = 1, type = StringType.class) StringType theContext,
      @OperationParam(name = "changedsince", min = 0, max = 1, type = InstantType.class) InstantType theChangedsince) {

    List<String> warnings = new ArrayList<>();

    // Coverage null check
    if (theCoverage == null) {
      throw new InvalidRequestException(
          "coverage parameter is required",
          OperationOutcomeBuilder.createBadRequestOutcome("The 'coverage' parameter is required (min=1)."));
    }

    boolean hasQuestionnaires = theQuestionnaires != null && !theQuestionnaires.isEmpty();
    boolean hasOrders = theOrders != null && !theOrders.isEmpty();
    boolean hasContext = theContext != null && !theContext.isEmpty();

    // At least one of questionnaire, order, context (oper-6)
    if (!hasQuestionnaires && !hasOrders && !hasContext) {
      throw new InvalidRequestException(
          "At least one of 'questionnaire', 'order', or 'context' is required (oper-6)",
          OperationOutcomeBuilder.createOperationOutcome(
              IssueSeverity.ERROR, IssueType.REQUIRED,
              "oper-6 constraint violation",
              "At least one of 'questionnaire', 'order', or 'context' must be provided."));
    }

    // Context-only: not currently supported
    if (hasContext && !hasQuestionnaires && !hasOrders) {
      throw new InvalidRequestException(
          "context-only requests are not supported",
          OperationOutcomeBuilder.createOperationOutcome(
              IssueSeverity.ERROR, IssueType.NOTSUPPORTED,
              "not-supported",
              "The 'context' parameter alone is not currently supported. Provide 'questionnaire' or 'order' parameters."));
    }

    // Order type validation: partition into valid/unsupported
    if (hasOrders) {
      List<IAnyResource> validOrders = new ArrayList<>();
      List<String> unsupportedTypes = new ArrayList<>();

      for (IAnyResource order : theOrders) {
        String resourceType = ((Resource) order).fhirType();
        if (SUPPORTED_ORDER_TYPES.contains(resourceType)) {
          validOrders.add(order);
        } else {
          unsupportedTypes.add(resourceType);
        }
      }

      if (!unsupportedTypes.isEmpty()) {
        String msg = "Unsupported order type(s): " + String.join(", ", unsupportedTypes);
        if (validOrders.isEmpty() && !hasQuestionnaires) {
          // All orders unsupported and no questionnaires to fall back on
          throw new InvalidRequestException(
              msg,
              OperationOutcomeBuilder.createBadRequestOutcome(
                  msg + ". No processable parameters remain."));
        }
        warnings.add(msg + ". These orders were ignored.");
        logger.warn(msg);
      }
    }

    // Context alongside other params: note for informational warning
    if (hasContext && (hasQuestionnaires || hasOrders)) {
      warnings.add("The 'context' parameter was provided but is not yet supported; it was ignored.");
    }

    // Build response with outcome containing any warnings
    Parameters result = new Parameters();

    if (!warnings.isEmpty()) {
      OperationOutcome outcome = new OperationOutcome();
      for (String warning : warnings) {
        outcome.addIssue()
            .setSeverity(IssueSeverity.WARNING)
            .setCode(IssueType.INFORMATIONAL)
            .setDiagnostics(warning);
      }
      result.addParameter().setName("outcome").setResource(outcome);
    }
    
    // TODO: implement actual questionnaire package generation logic here, using the validated parameters
    
    return result;
  }
}
