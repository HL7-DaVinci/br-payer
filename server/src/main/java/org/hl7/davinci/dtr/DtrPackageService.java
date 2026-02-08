package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the DTR $questionnaire-package operation pipeline.
 * Coordinates questionnaire resolution, sub-questionnaire assembly, library resolution,
 * value set collection, bundle assembly, and response building.
 */
@Service
public class DtrPackageService {

  private static final Logger logger = LoggerFactory.getLogger(DtrPackageService.class);

  private static final String OUTPUT_PROFILE =
      "http://hl7.org/fhir/us/davinci-dtr/StructureDefinition/dtr-qpackage-output-parameters";

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
   * @return Parameters with packagebundle(s) and optional outcome
   */
  public Parameters generatePackages(
      Coverage coverage,
      List<Resource> validOrders,
      List<CanonicalType> questionnaireCanonicals,
      InstantType changedsince) {

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
        Bundle packageBundle = processQuestionnaire(rq, coverage, validOrders, changedsince, warnings);
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
      List<String> warnings) {

    // Copy to avoid mutating repository state
    Questionnaire questionnaire = rq.resource().copy();

    // Assemble sub-questionnaires
    List<String> subQWarnings = subQuestionnaireAssembler.assemble(questionnaire);
    warnings.addAll(subQWarnings);

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

    // Build QuestionnaireResponse
    List<Resource> orders = (validOrders != null) ? validOrders : List.of();
    QuestionnaireResponse qr = responseBuilder.buildResponse(questionnaire, coverage, rq, orders);

    // Assemble bundle
    DtrBundleAssembler.BundleResult bundleResult = bundleAssembler.assembleBundle(
        questionnaire, libraries, valueSets, qr);

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
    params.getMeta().addProfile(OUTPUT_PROFILE);

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
    }

    return params;
  }
}
