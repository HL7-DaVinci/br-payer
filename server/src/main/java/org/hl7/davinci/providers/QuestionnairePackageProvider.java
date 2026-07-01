package org.hl7.davinci.providers;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.BaseProvider;
import org.hl7.davinci.common.OrderResourceTypes;
import org.hl7.davinci.dtr.DtrConstants;
import org.hl7.davinci.dtr.DtrPackageService;
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
import jakarta.servlet.http.HttpServletRequest;

/**
 * Implements the DTR $questionnaire-package operation.
 *
 * @see <a href="http://hl7.org/fhir/us/davinci-dtr/OperationDefinition/questionnaire-package">
 *   DTR OperationDefinition</a>
 */
@Component
public class QuestionnairePackageProvider extends BaseProvider {

  private static final Logger logger = LoggerFactory.getLogger(QuestionnairePackageProvider.class);

  private final DtrPackageService dtrPackageService;

  private final HttpServletRequest servletRequest;

  public QuestionnairePackageProvider(DtrPackageService dtrPackageService, HttpServletRequest servletRequest) {
    this.dtrPackageService = dtrPackageService;
    this.servletRequest = servletRequest;
  }

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

    // Order type validation: partition into valid/unsupported
    List<Resource> validOrders = new ArrayList<>();
    if (hasOrders) {
      List<String> unsupportedTypes = new ArrayList<>();

      for (IAnyResource order : theOrders) {
        String resourceType = ((Resource) order).fhirType();
        if (OrderResourceTypes.isSupported(resourceType)) {
          validOrders.add((Resource) order);
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

    // A context (item trace number) names the questionnaire; discover it via DTR and skip the order
    List<CanonicalType> questionnaires = theQuestionnaires;
    if (hasContext && !hasQuestionnaires) {
      Questionnaire contextQuestionnaire = dtrPackageService.resolveContext(theContext.getValue());
      questionnaires = List.of(new CanonicalType(contextQuestionnaire.getUrl()));
      validOrders = new ArrayList<>();
    }
    if (hasContext && hasQuestionnaires) {
      warnings.add("Both 'context' and 'questionnaire' provided; the explicit questionnaire was used.");
    }

    // Extract adaptive mode header for dual-mode adaptive questionnaire packaging
    String adaptiveMode = servletRequest != null
        ? servletRequest.getHeader(DtrConstants.ADAPTIVE_MODE_HEADER)
        : null;

    // Delegate to service
    Parameters result = dtrPackageService.generatePackages(
        theCoverage, validOrders, questionnaires, theChangedsince, adaptiveMode);

    // Merge provider-level warnings into result outcome
    if (!warnings.isEmpty()) {
      mergeWarnings(result, warnings);
    }

    return result;
  }

  /**
   * Merge provider-level warnings into the Parameters result's outcome.
   * Creates or appends to the existing outcome OperationOutcome.
   */
  private void mergeWarnings(Parameters result, List<String> warnings) {
    // Find existing outcome parameter
    OperationOutcome outcome = null;
    for (Parameters.ParametersParameterComponent param : result.getParameter()) {
      if ("outcome".equals(param.getName()) && param.getResource() instanceof OperationOutcome oo) {
        outcome = oo;
        break;
      }
    }

    if (outcome == null) {
      outcome = new OperationOutcome();
      result.addParameter().setName("outcome").setResource(outcome);
    }

    for (String warning : warnings) {
      outcome.addIssue()
          .setSeverity(IssueSeverity.WARNING)
          .setCode(IssueType.INFORMATIONAL)
          .setDiagnostics(warning);
    }
  }
}
