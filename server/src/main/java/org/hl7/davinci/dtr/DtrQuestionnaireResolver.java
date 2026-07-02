package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.common.CoverageInfoUtil;
import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.FhirCodeExtractor;
import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Library;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

/**
 * Resolves Questionnaire resources for the DTR $questionnaire-package
 * operation.
 * Supports two resolution paths:
 * <ul>
 * <li>Questionnaire: Direct questionnaire canonical URL lookup</li>
 * <li>Order-based: Resolution via PlanDefinition evaluation</li>
 * </ul>
 * When both paths produce results, they are merged with deduplication.
 */
@Component
public class DtrQuestionnaireResolver {

  private static final Logger logger = LoggerFactory.getLogger(DtrQuestionnaireResolver.class);

  /**
   * Resource types that can be queried by patient/subject from the payer's JPA
   * claims store.
   */
  private static final Set<String> PATIENT_QUERYABLE_TYPES = Set.of(
      "Condition", "Observation", "Procedure", "Encounter",
      "MedicationRequest", "MedicationStatement", "MedicationDispense",
      "ServiceRequest", "DeviceRequest", "DiagnosticReport",
      "AllergyIntolerance", "Immunization");
  /** Resource types that do not have a "subject" search parameter */
  private static final Map<String, String> PATIENT_SEARCH_PARAM_BY_TYPE = Map.of(
      "AllergyIntolerance", "patient",
      "Immunization", "patient");

  private final DaoRegistry daoRegistry;
  private final PlanDefinitionService planDefinitionService;

  public DtrQuestionnaireResolver(DaoRegistry daoRegistry, PlanDefinitionService planDefinitionService) {
    this.daoRegistry = daoRegistry;
    this.planDefinitionService = planDefinitionService;
  }

  public enum ResolutionPath {
    QUESTIONNAIRE, ORDER, BOTH
  }

  public record ResolutionResult(List<ResolvedQuestionnaire> questionnaires, List<String> warnings) {
  }

  /** Provenance metadata per resolved questionnaire */
  public record ResolvedQuestionnaire(
      String canonical,
      Questionnaire resource,
      ResolutionPath path,
      List<String> sourceOrderIds,
      String warning) {
    /** Create a copy with merged path and source order IDs */
    ResolvedQuestionnaire mergeWith(ResolvedQuestionnaire other) {
      ResolutionPath mergedPath = (this.path != other.path) ? ResolutionPath.BOTH : this.path;
      List<String> mergedOrderIds = new ArrayList<>(this.sourceOrderIds);
      for (String id : other.sourceOrderIds) {
        if (!mergedOrderIds.contains(id)) {
          mergedOrderIds.add(id);
        }
      }
      // Prefer non-null resource and non-null warning
      Questionnaire mergedResource = this.resource != null ? this.resource : other.resource;
      String mergedWarning = this.warning != null ? this.warning : other.warning;
      return new ResolvedQuestionnaire(this.canonical, mergedResource, mergedPath, mergedOrderIds, mergedWarning);
    }
  }

  /**
   * Resolve questionnaires from canonicals and/or orders.
   *
   * @param canonicals  explicit questionnaire canonical URLs (questionnaire
   *                    parameter), may be null
   * @param validOrders order resources for PlanDefinition evaluation (order-based
   *                    resolution), may be null/empty
   * @param coverage    the Coverage resource
   * @return deduplicated list with provenance metadata
   */
  public ResolutionResult resolve(
      List<CanonicalType> canonicals,
      List<Resource> validOrders,
      Coverage coverage) {

    Map<String, ResolvedQuestionnaire> results = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    boolean hasQuestionnaire = canonicals != null && !canonicals.isEmpty();
    boolean hasOrder = validOrders != null && !validOrders.isEmpty();

    // Questionnaire parameter: Direct canonical lookup
    if (hasQuestionnaire) {
      for (CanonicalType canonical : canonicals) {
        String canonicalValue = canonical.getValue();
        Questionnaire q = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonicalValue);

        String warning = null;
        if (q == null) {
          warning = "Questionnaire not found: " + canonicalValue;
          logger.warn(warning);
        }

        // Normalize to version-specific key
        String key = (q != null)
            ? FhirUtil.toVersionSpecific(q.getUrl(), q.getVersion())
            : canonicalValue;

        ResolvedQuestionnaire resolved = new ResolvedQuestionnaire(
            key, q, ResolutionPath.QUESTIONNAIRE, new ArrayList<>(), warning);
        mergeInto(results, key, resolved);
      }
    }

    // Order-based: Resolution via PlanDefinition evaluation
    if (hasOrder) {
      resolveViaOrders(validOrders, coverage, results, warnings);
    }

    // Mismatch detection: both paths active with no canonical overlap
    if (hasQuestionnaire && hasOrder) {
      Set<String> questionnaireKeys = new HashSet<>();
      Set<String> orderKeys = new HashSet<>();
      for (ResolvedQuestionnaire rq : results.values()) {
        if (rq.path == ResolutionPath.QUESTIONNAIRE)
          questionnaireKeys.add(rq.canonical);
        else if (rq.path == ResolutionPath.ORDER)
          orderKeys.add(rq.canonical);
        // BOTH means overlap exists
      }
      boolean anyOverlap = results.values().stream()
          .anyMatch(rq -> rq.path == ResolutionPath.BOTH);
      if (!anyOverlap && !questionnaireKeys.isEmpty() && !orderKeys.isEmpty()) {
        logger.info("No overlap between questionnaire and order parameter resolution results");
      }
    }

    return new ResolutionResult(new ArrayList<>(results.values()), warnings);
  }

  /** Resolves a questionnaire context id (item trace number) to its Questionnaire. */
  public Questionnaire resolveContext(String context) {
    try {
      return daoRegistry.getResourceDao(Questionnaire.class)
          .read(new IdType("Questionnaire", context), new SystemRequestDetails());
    } catch (ResourceNotFoundException e) {
      throw new UnprocessableEntityException(
          "Unresolvable questionnaire context (item trace number): " + context);
    }
  }

  private void resolveViaOrders(List<Resource> validOrders, Coverage coverage,
      Map<String, ResolvedQuestionnaire> results, List<String> warnings) {

    // Resolve Patient from coverage beneficiary
    Patient patient = resolvePatient(coverage);
    if (patient == null) {
      String warning = "Could not resolve patient from Coverage beneficiary; skipping order-based resolution";
      logger.warn(warning);
      warnings.add(warning);
      return;
    }
    String patientId = patient.getIdElement().getIdPart();

    // Resolve payor identifiers
    List<Identifier> payorIdentifiers = extractPayorIdentifiers(coverage);
    if (payorIdentifiers.isEmpty()) {
      String warning = "No payor identifiers found on Coverage; skipping order-based resolution";
      logger.warn(warning);
      warnings.add(warning);
      return;
    }

    // Build base data bundle (Patient, Coverage, orders -- no clinical data yet)
    Bundle dataBundle = buildDataBundle(patient, coverage, validOrders);
    String subjectRef = resolveSubjectReference(coverage, patient);
    Set<String> fetchedClinicalTypes = new HashSet<>();
    Set<String> inputOrderTypes = new HashSet<>();
    for (Resource order : validOrders) {
      inputOrderTypes.add(order.fhirType());
    }

    for (Resource order : validOrders) {
      String orderId = order.getIdElement().toUnqualifiedVersionless().getValue();
      Resource resolvedItem = resolveItemReference(order);
      List<Coding> codes = FhirCodeExtractor.extractCodes(order, true, resolvedItem);

      // Collect and deduplicate PlanDefinitions across codes
      Map<String, PlanDefinition> uniquePlans = new LinkedHashMap<>();
      for (Coding code : codes) {
        List<PlanDefinition> plans = planDefinitionService.findPlanDefinitions(code, payorIdentifiers, null);
        for (PlanDefinition plan : plans) {
          String planId = plan.getIdElement().getIdPart();
          uniquePlans.putIfAbsent(planId, plan);
        }
      }

      // Filter dispatch plans as needed
      List<String> stageNotes = planDefinitionService.removeDispatchPlansLackingEvidence(
          uniquePlans.values(), order);
      stageNotes.forEach(logger::info);
      warnings.addAll(stageNotes);

      if (uniquePlans.isEmpty()) {
        String warning = "No PlanDefinitions matched for order " + orderId + " (oper-9)";
        logger.warn(warning);
        warnings.add(warning);
        continue;
      }

      boolean anyQuestionnaireFound = false;
      boolean coverageInfoProduced = false;
      boolean questionnaireExpectedButMissing = false;

      for (PlanDefinition plan : uniquePlans.values()) {
        try {
          // Fetch clinical data required by this PlanDefinition's libraries
          Set<String> requiredTypes = resolveRequiredClinicalTypes(plan);
          Set<String> newTypes = new HashSet<>(requiredTypes);
          // Keep explicit request resources as primary CQL context for First([Type])
          // queries; avoid replacing them with unrelated repository resources.
          newTypes.remove("Patient");
          newTypes.remove("Coverage");
          newTypes.removeAll(inputOrderTypes);
          newTypes.removeAll(fetchedClinicalTypes);
          if (!newTypes.isEmpty()) {
            includePatientClinicalData(dataBundle, subjectRef, newTypes);
            fetchedClinicalTypes.addAll(newTypes);
          }

          RequestGroup requestGroup = planDefinitionService.applyPlanDefinition(
              plan, patientId, dataBundle, null);

          List<Extension> coverageInfoExts = extractCoverageInfoExtensions(requestGroup);
          if (coverageInfoExts.isEmpty()) {
            logger.debug("PlanDefinition {} produced no coverage-information extension", plan.getId());
            continue;
          }

          // Extract questionnaire canonicals from all coverage-information extensions
          for (Extension coverageInfoExt : coverageInfoExts) {
            coverageInfoProduced = true;
            List<Extension> questionnaireExts = coverageInfoExt.getExtensionsByUrl("questionnaire");
            if (questionnaireExts.isEmpty()) {
              if (hasDocNeeded(coverageInfoExt)) {
                questionnaireExpectedButMissing = true;
              }
              continue;
            }
            for (Extension qExt : questionnaireExts) {
              if (qExt.getValue() instanceof CanonicalType canonicalType) {
                String canonicalValue = canonicalType.getValue();
                Questionnaire q = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonicalValue);

                if (q == null) {
                  String warning = "Questionnaire from PlanDefinition evaluation not found: " + canonicalValue;
                  logger.warn(warning);
                  warnings.add(warning);
                  continue;
                }

                // Check expiry for order-based resolution
                if (isExpired(q) && !results.containsKey(canonicalValue)) {
                  // Order-based: expired questionnaires are excluded unless questionnaire
                  // parameter already included them
                  String key = FhirUtil.toVersionSpecific(q.getUrl(), q.getVersion());
                  if (!results.containsKey(key)) {
                    logger.info("Excluding expired questionnaire from order-based resolution: {}", canonicalValue);
                    continue;
                  }
                }

                String key = FhirUtil.toVersionSpecific(q.getUrl(), q.getVersion());

                // A completed QuestionnaireResponse already on file for this
                // questionnaire and order means the documentation requirement is
                // satisfied. Explicitly requested questionnaires are
                // still returned.
                if (!results.containsKey(key)
                    && hasCompletedResponseOnFile(q.getUrl(), subjectRef, orderId)) {
                  String note = "Excluding questionnaire " + canonicalValue + " for order " + orderId
                      + ": a completed QuestionnaireResponse is already on file";
                  logger.info(note);
                  warnings.add(note);
                  continue;
                }

                List<String> orderIds = new ArrayList<>();
                orderIds.add(orderId);
                ResolvedQuestionnaire resolved = new ResolvedQuestionnaire(
                    key, q, ResolutionPath.ORDER, orderIds, null);
                mergeInto(results, key, resolved);
                anyQuestionnaireFound = true;
              }
            }
          }
        } catch (Exception e) {
          String warning = "PlanDefinition evaluation failed for " + plan.getId() + ": " + e.getMessage();
          logger.warn(warning, e);
          warnings.add(warning);
        }
      }

      if (!anyQuestionnaireFound && (!coverageInfoProduced || questionnaireExpectedButMissing)) {
        String warning = "Order " + orderId + " produced no questionnaires after PlanDefinition evaluation";
        logger.warn(warning);
        warnings.add(warning);
      }
    }
  }

  private Patient resolvePatient(Coverage coverage) {
    if (coverage == null || !coverage.hasBeneficiary()) {
      return null;
    }
    String patientRef = coverage.getBeneficiary().getReference();
    if (patientRef == null) {
      return null;
    }
    String idPart = new org.hl7.fhir.r4.model.IdType(patientRef).getIdPart();
    try {
      return daoRegistry.getResourceDao(Patient.class)
          .read(new org.hl7.fhir.r4.model.IdType("Patient", idPart),
              new ca.uhn.fhir.rest.api.server.SystemRequestDetails());
    } catch (Exception e) {
      // DTR requests originate from the EHR; the patient may not exist on the payer
      // server.
      // Create a stub so PlanDefinition evaluation has a subject context.
      logger.debug("Patient {} not in repository, using stub for PlanDefinition evaluation", idPart);
      Patient stub = new Patient();
      stub.setId(idPart);
      return stub;
    }
  }

  private List<Identifier> extractPayorIdentifiers(Coverage coverage) {
    List<Identifier> identifiers = new ArrayList<>();
    if (coverage == null || !coverage.hasPayor()) {
      return identifiers;
    }
    for (Reference payorRef : coverage.getPayor()) {
      if (!payorRef.hasReference()) {
        continue;
      }
      Organization org = ResourceResolver.resolveTypedReferenceFromDao(
          payorRef, Organization.class, coverage, daoRegistry);
      if (org != null) {
        PayorIdentifierUtil.addValidIdentifiers(identifiers, org);
      }
    }
    return identifiers;
  }

  private Resource resolveItemReference(Resource order) {
    return FhirCodeExtractor.resolveReferencedItem(order, itemRef -> {
      DomainResource parent = order instanceof DomainResource domainResource ? domainResource : null;
      return ResourceResolver.resolveReferenceFromDao(itemRef, parent, daoRegistry);
    });
  }

  private Bundle buildDataBundle(Patient patient, Coverage coverage, List<Resource> orders) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(patient);
    bundle.addEntry().setResource(coverage);
    for (Resource order : orders) {
      bundle.addEntry().setResource(order);
    }
    return bundle;
  }

  /**
   * Reads a PlanDefinition's Library references and extracts the
   * patient-queryable
   * resource types declared in their dataRequirement entries.
   */
  private Set<String> resolveRequiredClinicalTypes(PlanDefinition planDefinition) {
    Set<String> types = new HashSet<>();
    if (!planDefinition.hasLibrary()) {
      return types;
    }
    for (CanonicalType libRef : planDefinition.getLibrary()) {
      try {
        String ref = libRef.getValue();
        if (ref == null || ref.isBlank()) {
          continue;
        }

        // PlanDefinition.library may include a version suffix (e.g.,
        // "Library/Foo|1.0.0").
        // Strip it before resolving by resource ID.
        String[] canonicalParts = FhirUtil.parseCanonical(ref);
        if (canonicalParts.length == 0 || canonicalParts[0] == null || canonicalParts[0].isBlank()) {
          continue;
        }
        IdType idType = new IdType(canonicalParts[0]);
        String idPart = idType.getIdPart();
        if (idPart == null || idPart.isBlank()) {
          continue;
        }

        Library library = (Library) daoRegistry.getResourceDao("Library")
            .read(new IdType("Library", idPart),
                new ca.uhn.fhir.rest.api.server.SystemRequestDetails());

        if (library != null && library.hasDataRequirement()) {
          for (DataRequirement dr : library.getDataRequirement()) {
            if (dr.hasType()) {
              String type = dr.getType();
              if (PATIENT_QUERYABLE_TYPES.contains(type)) {
                types.add(type);
              }
            }
          }
        }
      } catch (Exception e) {
        logger.debug("Could not resolve Library {} for data requirements: {}",
            libRef.getValue(), e.getMessage());
      }
    }
    return types;
  }

  /**
   * Queries the payer's JPA store for the patient's clinical data and adds it
   * to the evaluation bundle. Only fetches the resource types declared in the
   * PlanDefinition's Library dataRequirement entries, skipping resources already
   * present in the bundle to prevent duplicates.
   */
  private void includePatientClinicalData(Bundle bundle, String subjectRef, Set<String> resourceTypes) {
    if (subjectRef == null || subjectRef.isBlank() || resourceTypes.isEmpty()) {
      return;
    }

    // Collect IDs already in the bundle to prevent duplicates
    Set<String> existingIds = new HashSet<>();
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      if (entry.hasResource()) {
        String id = entry.getResource().getIdElement().toUnqualifiedVersionless().getValue();
        if (id != null) {
          existingIds.add(id);
        }
      }
    }

    for (String resourceType : resourceTypes) {
      try {
        String searchParam = patientSearchParam(resourceType);
        var searchParams = new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
        searchParams.add(searchParam,
            new ca.uhn.fhir.rest.param.ReferenceParam(subjectRef));
        searchParams.setLoadSynchronous(true);
        var resources = daoRegistry.getResourceDao(resourceType)
            .searchForResources(searchParams, new ca.uhn.fhir.rest.api.server.SystemRequestDetails());

        int added = 0;
        for (var resource : resources) {
          String id = ((Resource) resource).getIdElement().toUnqualifiedVersionless().getValue();
          if (id == null || !existingIds.contains(id)) {
            bundle.addEntry().setResource((Resource) resource);
            if (id != null) {
              existingIds.add(id);
            }
            added++;
          }
        }

        if (added > 0) {
          logger.debug("Added {} {} resources for {} {} to evaluation bundle",
              added, resourceType, searchParam, subjectRef);
        }
      } catch (Exception e) {
        logger.debug("Could not query {} for patient {} using {}: {}",
            resourceType, subjectRef, patientSearchParam(resourceType), e.getMessage());
      }
    }
  }

  private String patientSearchParam(String resourceType) {
    return PATIENT_SEARCH_PARAM_BY_TYPE.getOrDefault(resourceType, "subject");
  }

  private String resolveSubjectReference(Coverage coverage, Patient patient) {
    String beneficiaryRef = (coverage != null) ? toVersionlessPatientReference(coverage.getBeneficiary()) : null;
    if (beneficiaryRef != null) {
      return beneficiaryRef;
    }

    if (patient == null || !patient.hasIdElement()) {
      return null;
    }

    var patientId = patient.getIdElement();
    String idPart = patientId.getIdPart();
    if (idPart == null || idPart.isBlank()) {
      return null;
    }

    String versionlessRef = patientId.toVersionless().getValue();
    if (versionlessRef != null && !versionlessRef.isBlank()) {
      return versionlessRef;
    }

    return "Patient/" + idPart;
  }

  private String toVersionlessPatientReference(Reference reference) {
    return ResourceResolver.toVersionlessTypedReference(reference, "Patient");
  }

  /**
   * Checks whether a completed QuestionnaireResponse for the given questionnaire
   * canonical exists whose qr-context extension references the given order.
   * Searches by subject and compares canonicals in code because stored
   * QuestionnaireResponse.questionnaire values are version-specific (oper-16).
   */
  private boolean hasCompletedResponseOnFile(String questionnaireUrl, String subjectRef, String orderId) {
    if (subjectRef == null || subjectRef.isBlank()) {
      return false;
    }
    try {
      var searchParams = new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
      searchParams.add("subject", new ca.uhn.fhir.rest.param.ReferenceParam(subjectRef));
      searchParams.add("status", new ca.uhn.fhir.rest.param.TokenParam("completed"));
      searchParams.setLoadSynchronous(true);
      var responses = daoRegistry.getResourceDao(QuestionnaireResponse.class)
          .searchForResources(searchParams, new SystemRequestDetails());

      for (var response : responses) {
        QuestionnaireResponse qr = (QuestionnaireResponse) response;
        if (!qr.hasQuestionnaire()) {
          continue;
        }
        String[] storedParts = FhirUtil.parseCanonical(qr.getQuestionnaire());
        if (storedParts.length == 0 || !questionnaireUrl.equals(storedParts[0])) {
          continue;
        }
        for (Extension contextExt : qr.getExtensionsByUrl(DtrConstants.QR_CONTEXT_EXT)) {
          if (contextExt.getValue() instanceof Reference ref && ref.hasReference()
              && orderId.equals(new IdType(ref.getReference()).toUnqualifiedVersionless().getValue())) {
            return true;
          }
        }
      }
    } catch (Exception e) {
      logger.debug("Could not search QuestionnaireResponses for {}: {}", questionnaireUrl, e.getMessage());
    }
    return false;
  }

  private boolean isExpired(Questionnaire q) {
    if (!q.hasEffectivePeriod() || !q.getEffectivePeriod().hasEnd()) {
      return false;
    }
    return q.getEffectivePeriod().getEnd().before(new Date());
  }

  private void mergeInto(Map<String, ResolvedQuestionnaire> results, String key,
      ResolvedQuestionnaire incoming) {
    ResolvedQuestionnaire existing = results.get(key);
    if (existing != null) {
      results.put(key, existing.mergeWith(incoming));
    } else {
      results.put(key, incoming);
    }
  }

  /**
   * Extracts all coverage-information extensions from RequestGroup actions.
   */
  private List<Extension> extractCoverageInfoExtensions(RequestGroup requestGroup) {
    return CoverageInfoUtil.extractCoverageInfoExtensions(requestGroup);
  }

  private boolean hasDocNeeded(Extension coverageInfoExt) {
    for (Extension ext : coverageInfoExt.getExtension()) {
      if ("doc-needed".equals(ext.getUrl()) && ext.hasValue()) {
        return true;
      }
    }
    return false;
  }
}
