package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Expression;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.UrlType;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import static org.hl7.davinci.common.FhirConstants.*;
import static org.hl7.davinci.dtr.DtrConstants.*;

/**
 * Orchestrates the DTR $questionnaire-package operation pipeline.
 * Coordinates questionnaire resolution, sub-questionnaire assembly, library resolution,
 * value set collection, bundle assembly, and response building.
 */
@Service
public class DtrPackageService {

  private static final Logger logger = LoggerFactory.getLogger(DtrPackageService.class);

  private static final String DEFAULT_NEXT_QUESTION_URL =
      "http://localhost:8080/fhir/Questionnaire/$next-question";

  private final DtrQuestionnaireResolver questionnaireResolver;
  private final DtrSubQuestionnaireAssembler subQuestionnaireAssembler;
  private final DtrLibraryResolver libraryResolver;
  private final DtrValueSetCollector valueSetCollector;
  private final DtrBundleAssembler bundleAssembler;
  private final DtrResponseBuilder responseBuilder;

  public DtrPackageService(
      DtrQuestionnaireResolver questionnaireResolver,
      DtrSubQuestionnaireAssembler subQuestionnaireAssembler,
      DtrLibraryResolver libraryResolver,
      DtrValueSetCollector valueSetCollector,
      DtrBundleAssembler bundleAssembler,
      DtrResponseBuilder responseBuilder) {
    this.questionnaireResolver = questionnaireResolver;
    this.subQuestionnaireAssembler = subQuestionnaireAssembler;
    this.libraryResolver = libraryResolver;
    this.valueSetCollector = valueSetCollector;
    this.bundleAssembler = bundleAssembler;
    this.responseBuilder = responseBuilder;
  }

  /**
   * Generate questionnaire packages.
   *
   * @param coverage                the Coverage resource (required)
   * @param validOrders             validated order resources (may be null/empty)
   * @param questionnaireCanonicals explicit questionnaire canonicals (may be null/empty)
   * @param changedsince            only include packages modified after this instant (may be null)
   * @param adaptiveMode            header override: "search" forces adapt-search, "initial" forces
   *                                initial items, null uses structural analysis (default)
   * @return Parameters with packagebundle(s) and optional outcome
   */
  public Parameters generatePackages(
      Coverage coverage,
      List<Resource> validOrders,
      List<CanonicalType> questionnaireCanonicals,
      InstantType changedsince,
      String adaptiveMode) {

    List<String> warnings = new ArrayList<>();

    // Step 1: Resolve questionnaires
    DtrQuestionnaireResolver.ResolutionResult resolution =
        questionnaireResolver.resolve(questionnaireCanonicals, validOrders, coverage);
    warnings.addAll(resolution.warnings());

    // Collect per-questionnaire resolution warnings
    for (DtrQuestionnaireResolver.ResolvedQuestionnaire rq : resolution.questionnaires()) {
      if (rq.warning() != null) {
        warnings.add(rq.warning());
      }
    }

    // Step 2: Process each resolved questionnaire into a package bundle
    List<Bundle> packageBundles = new ArrayList<>();

    for (DtrQuestionnaireResolver.ResolvedQuestionnaire rq : resolution.questionnaires()) {
      if (rq.resource() == null) {
        continue; // Already warned during resolution
      }

      try {
        Bundle packageBundle = processQuestionnaire(rq, coverage, validOrders, changedsince, adaptiveMode, warnings);
        if (packageBundle != null) {
          packageBundles.add(packageBundle);
        }
      } catch (Exception e) {
        String warning = "Error processing questionnaire " + rq.canonical() + ": " + e.getMessage();
        logger.warn(warning, e);
        warnings.add(warning);
      }
    }

    // Step 3: Build output Parameters
    return buildOutputParameters(packageBundles, warnings);
  }

  private Bundle processQuestionnaire(
      DtrQuestionnaireResolver.ResolvedQuestionnaire rq,
      Coverage coverage,
      List<Resource> validOrders,
      InstantType changedsince,
      String adaptiveMode,
      List<String> warnings) {

    // Copy to avoid mutating repository state
    Questionnaire questionnaire = rq.resource().copy();
    boolean isAdaptiveQuestionnaire = DtrResponseBuilder.isAdaptiveQuestionnaire(questionnaire);

    // Assemble sub-questionnaires
    List<String> subQWarnings = subQuestionnaireAssembler.assemble(questionnaire);
    warnings.addAll(subQWarnings);

    // Normalize $next-question URL if necessary
    normalizeAdaptiveQuestionnaireUrl(questionnaire);

    // Resolve libraries
    DtrLibraryResolver.LibraryResolution libraryResult = libraryResolver.resolveLibraries(questionnaire);
    warnings.addAll(libraryResult.warnings());
    List<Library> libraries = libraryResult.libraries();

    // Collect value sets
    DtrValueSetCollector.ValueSetCollection vsResult = valueSetCollector.collectValueSets(questionnaire, libraries);
    warnings.addAll(vsResult.warnings());
    List<ValueSet> valueSets = vsResult.valueSets();

    // changedsince filtering
    if (changedsince != null && changedsince.hasValue()) {
      Date threshold = changedsince.getValue();
      Date maxLastUpdated = computeMaxLastUpdated(questionnaire, libraries, valueSets);
      if (maxLastUpdated != null && maxLastUpdated.before(threshold)) {
        logger.info("Questionnaire {} unchanged since {}, skipping", rq.canonical(), changedsince.getValueAsString());
        return null;
      }
    }

    // Build QuestionnaireResponse adaptive or standard path
    List<Resource> orders = (validOrders != null) ? validOrders : List.of();
    QuestionnaireResponse qr;
    List<String> qrWarnings;

    List<QuestionnaireItemComponent> initialItems = List.of();
    if (isAdaptiveQuestionnaire) {
      initialItems = resolveInitialItems(questionnaire, adaptiveMode);
      var adaptiveResult = responseBuilder.buildAdaptiveResponse(
          questionnaire, coverage, rq, orders, initialItems);
      qr = adaptiveResult.response();
      qrWarnings = adaptiveResult.warnings();
    } else {
      var prepopResult = responseBuilder.buildResponse(
          questionnaire, coverage, rq, orders, libraries);
      qr = prepopResult.response();
      qrWarnings = prepopResult.warnings();
    }

    warnings.addAll(qrWarnings);

    // Keep expression references for pre-population execution, then strip before
    // packaging to avoid unresolved canonical reference errors in standalone
    // bundle validation.
    stripExpressionReferences(questionnaire.getItem());

    // Dual-mode adaptive packaging: include initial items when available,
    // otherwise use item-less adapt-search profile.
    Questionnaire bundleQuestionnaire = questionnaire;
    if (isAdaptiveQuestionnaire) {
      if (initialItems.isEmpty()) {
        bundleQuestionnaire = createAdaptiveSearchQuestionnaire(questionnaire);
      } else {
        bundleQuestionnaire = createAdaptiveInitialQuestionnaire(questionnaire, initialItems);
      }
    }

    // Assemble bundle
    DtrBundleAssembler.BundleResult bundleResult = bundleAssembler.assembleBundle(
        bundleQuestionnaire, libraries, valueSets, qr);

    if (bundleResult.error() != null) {
      warnings.add(bundleResult.error());
      return null;
    }

    return bundleResult.bundle();
  }

  private Date computeMaxLastUpdated(Questionnaire questionnaire, List<Library> libraries,
      List<ValueSet> valueSets) {
    Date max = getLastUpdated(questionnaire);
    for (Library lib : libraries) {
      Date libDate = getLastUpdated(lib);
      if (libDate != null && (max == null || libDate.after(max))) {
        max = libDate;
      }
    }
    for (ValueSet vs : valueSets) {
      Date vsDate = getLastUpdated(vs);
      if (vsDate != null && (max == null || vsDate.after(max))) {
        max = vsDate;
      }
    }
    return max;
  }

  private Date getLastUpdated(Resource resource) {
    if (resource.hasMeta() && resource.getMeta().hasLastUpdated()) {
      return resource.getMeta().getLastUpdated();
    }
    return null;
  }

  private Parameters buildOutputParameters(List<Bundle> packageBundles, List<String> warnings) {
    Parameters params = new Parameters();
    params.getMeta().addProfile(QPACKAGE_OUTPUT_PROFILE);

    for (Bundle bundle : packageBundles) {
      params.addParameter().setName("packagebundle").setResource(bundle);
    }

    if (!warnings.isEmpty()) {
      OperationOutcome outcome = new OperationOutcome();
      for (String warning : warnings) {
        outcome.addIssue()
            .setSeverity(IssueSeverity.WARNING)
            .setCode(IssueType.INFORMATIONAL)
            .setDiagnostics(warning);
      }
      params.addParameter().setName("outcome").setResource(outcome);
    } else if (packageBundles.isEmpty()) {
      OperationOutcome outcome = new OperationOutcome();
      outcome.addIssue()
          .setSeverity(IssueSeverity.INFORMATION)
          .setCode(IssueType.INFORMATIONAL)
          .setDiagnostics("No questionnaires matched the request context.");
      params.addParameter().setName("outcome").setResource(outcome);
    }

    return params;
  }

  private void normalizeAdaptiveQuestionnaireUrl(Questionnaire questionnaire) {
    Extension adaptiveExt = questionnaire.getExtensionByUrl(QUESTIONNAIRE_ADAPTIVE_EXT);
    if (adaptiveExt == null) {
      return;
    }

    String targetUrl = responseBuilder.resolveNextQuestionUrl();
    if (targetUrl == null || targetUrl.isBlank()) {
      targetUrl = DEFAULT_NEXT_QUESTION_URL;
    }

    String currentUrl = adaptiveExt.hasValue() ? adaptiveExt.getValue().primitiveValue() : null;
    if (targetUrl.equals(currentUrl)) {
      return;
    }

    adaptiveExt.setValue(new UrlType(targetUrl));
    logger.info("Normalized adaptive questionnaire URL from {} to {}", currentUrl, targetUrl);
  }

  /**
   * Creates an item-less copy of the questionnaire for the adaptive bundle entry.
   * The dtr-questionnaire-adapt-search profile requires item 0..0; items are
   * delivered exclusively via $next-question.
   */
  private Questionnaire createAdaptiveSearchQuestionnaire(Questionnaire source) {
    Questionnaire shell = source.copy();
    shell.getItem().clear();
    shell.getMeta().getProfile().clear();
    shell.getMeta().addProfile(Q_ADAPT_SEARCH_PROFILE);
    return shell;
  }

  /**
   * Determines which initial items to include based on the adaptive mode.
   * "search" header forces empty; "initial" forces structural analysis.
   * Default (null) defers to the questionnaire's declared profile, falling back
   * to structural analysis when no recognized profile is present.
   */
  List<QuestionnaireItemComponent> resolveInitialItems(Questionnaire questionnaire, String adaptiveMode) {
    if ("search".equalsIgnoreCase(adaptiveMode)) {
      return List.of();
    }
    if ("initial".equalsIgnoreCase(adaptiveMode)) {
      return collectInitialItems(questionnaire);
    }
    if (questionnaire.hasMeta() && questionnaire.getMeta().hasProfile(Q_ADAPT_SEARCH_PROFILE)) {
      return List.of();
    }
    return collectInitialItems(questionnaire);
  }

  /**
   * Walks top-level items, collecting groups until hitting one with enableWhen.
   * This mirrors the $next-question delivery algorithm which checks group-level
   * enableWhen to determine conditional delivery boundaries.
   */
  List<QuestionnaireItemComponent> collectInitialItems(Questionnaire questionnaire) {
    List<QuestionnaireItemComponent> result = new ArrayList<>();
    for (QuestionnaireItemComponent item : questionnaire.getItem()) {
      if (item.hasEnableWhen()) {
        break;
      }
      result.add(item.copy());
    }
    return result;
  }

  /**
   * Creates a copy of the questionnaire with only the initial items,
   * using the dtr-questionnaire-adapt profile (allows items).
   */
  private Questionnaire createAdaptiveInitialQuestionnaire(
      Questionnaire source, List<QuestionnaireItemComponent> initialItems) {
    Questionnaire shell = source.copy();
    shell.getItem().clear();
    for (QuestionnaireItemComponent item : initialItems) {
      shell.addItem(item);
    }
    shell.getMeta().getProfile().clear();
    shell.getMeta().addProfile(Q_ADAPT_PROFILE);
    return shell;
  }

  private void stripExpressionReferences(List<QuestionnaireItemComponent> items) {
    if (items == null) {
      return;
    }
    for (QuestionnaireItemComponent item : items) {
      for (Extension ext : item.getExtension()) {
        if (!CQL_EXPRESSION_EXT_URLS.contains(ext.getUrl()) || !(ext.getValue() instanceof Expression expression)) {
          continue;
        }
        if (expression.hasReference()) {
          expression.setReference(null);
        }
      }
      stripExpressionReferences(item.getItem());
    }
  }

}
