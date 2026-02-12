package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.common.FhirCodeExtractor;
import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CanonicalType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;

/**
 * Resolves Questionnaire resources for the DTR $questionnaire-package operation.
 * Supports two resolution paths:
 * <ul>
 *   <li>Questionnaire: Direct questionnaire canonical URL lookup</li>
 *   <li>Order-based: Resolution via PlanDefinition evaluation</li>
 * </ul>
 * When both paths produce results, they are merged with deduplication.
 */
@Component
public class DtrQuestionnaireResolver {

  private static final Logger logger = LoggerFactory.getLogger(DtrQuestionnaireResolver.class);

  private final DaoRegistry daoRegistry;
  private final PlanDefinitionService planDefinitionService;

  public DtrQuestionnaireResolver(DaoRegistry daoRegistry, PlanDefinitionService planDefinitionService) {
    this.daoRegistry = daoRegistry;
    this.planDefinitionService = planDefinitionService;
  }

  public enum ResolutionPath { QUESTIONNAIRE, ORDER, BOTH }

  public record ResolutionResult(List<ResolvedQuestionnaire> questionnaires, List<String> warnings) {}

  /** Provenance metadata per resolved questionnaire */
  public record ResolvedQuestionnaire(
      String canonical,
      Questionnaire resource,
      ResolutionPath path,
      List<String> sourceOrderIds,
      String warning
  ) {
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
   * @param canonicals  explicit questionnaire canonical URLs (questionnaire parameter), may be null
   * @param validOrders order resources for PlanDefinition evaluation (order-based resolution), may be null/empty
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
        Questionnaire q = DtrFhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonicalValue);

        String warning = null;
        if (q == null) {
          warning = "Questionnaire not found: " + canonicalValue;
          logger.warn(warning);
        }

        // Normalize to version-specific key
        String key = (q != null)
            ? DtrFhirUtil.toVersionSpecific(q.getUrl(), q.getVersion())
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
        if (rq.path == ResolutionPath.QUESTIONNAIRE) questionnaireKeys.add(rq.canonical);
        else if (rq.path == ResolutionPath.ORDER) orderKeys.add(rq.canonical);
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

    // Build minimal data bundle
    Bundle dataBundle = buildDataBundle(patient, coverage, validOrders);

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

      if (uniquePlans.isEmpty()) {
        String warning = "No PlanDefinitions matched for order " + orderId + " (oper-9)";
        logger.warn(warning);
        warnings.add(warning);
        continue;
      }

      boolean anyQuestionnaireFound = false;

      for (PlanDefinition plan : uniquePlans.values()) {
        try {
          RequestGroup requestGroup = planDefinitionService.applyPlanDefinition(
              plan, patientId, dataBundle, null);

          List<Extension> coverageInfoExts = extractCoverageInfoExtensions(requestGroup);
          if (coverageInfoExts.isEmpty()) {
            logger.debug("PlanDefinition {} produced no coverage-information extension", plan.getId());
            continue;
          }

          // Extract questionnaire canonicals from all coverage-information extensions
          for (Extension coverageInfoExt : coverageInfoExts) {
            List<Extension> questionnaireExts = coverageInfoExt.getExtensionsByUrl("questionnaire");
            for (Extension qExt : questionnaireExts) {
              if (qExt.getValue() instanceof CanonicalType canonicalType) {
                String canonicalValue = canonicalType.getValue();
                Questionnaire q = DtrFhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonicalValue);

                if (q == null) {
                  String warning = "Questionnaire from PlanDefinition evaluation not found: " + canonicalValue;
                  logger.warn(warning);
                  warnings.add(warning);
                  continue;
                }

                // Check expiry for order-based resolution
                if (isExpired(q) && !results.containsKey(canonicalValue)) {
                  // Order-based: expired questionnaires are excluded unless questionnaire parameter already included them
                  String key = DtrFhirUtil.toVersionSpecific(q.getUrl(), q.getVersion());
                  if (!results.containsKey(key)) {
                    logger.info("Excluding expired questionnaire from order-based resolution: {}", canonicalValue);
                    continue;
                  }
                }

                String key = DtrFhirUtil.toVersionSpecific(q.getUrl(), q.getVersion());
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

      if (!anyQuestionnaireFound) {
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
          .read(new org.hl7.fhir.r4.model.IdType("Patient", idPart), new ca.uhn.fhir.rest.api.server.SystemRequestDetails());
    } catch (Exception e) {
      // DTR requests originate from the EHR; the patient may not exist on the payer server.
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

      String ref = payorRef.getReference();

      // Handle contained references (e.g., "#payor-org")
      if (ref.startsWith("#")) {
        String containedId = ref.substring(1);
        for (Resource contained : coverage.getContained()) {
          if (contained instanceof Organization org
              && containedId.equals(contained.getIdElement().getIdPart())) {
            for (Identifier id : org.getIdentifier()) {
              if (id.hasSystem() && id.hasValue()) {
                identifiers.add(id);
              }
            }
          }
        }
        continue;
      }

      // Handle external references
      try {
        String idPart = new org.hl7.fhir.r4.model.IdType(ref).getIdPart();
        Organization org = daoRegistry.getResourceDao(Organization.class)
            .read(new org.hl7.fhir.r4.model.IdType("Organization", idPart),
                new ca.uhn.fhir.rest.api.server.SystemRequestDetails());
        if (org != null) {
          for (Identifier id : org.getIdentifier()) {
            if (id.hasSystem() && id.hasValue()) {
              identifiers.add(id);
            }
          }
        }
      } catch (Exception e) {
        logger.warn("Could not resolve payor organization {}: {}", ref, e.getMessage());
      }
    }
    return identifiers;
  }

  private Resource resolveItemReference(Resource order) {
    Reference itemRef = null;
    if (order instanceof MedicationRequest medRequest && medRequest.hasMedicationReference()) {
      itemRef = medRequest.getMedicationReference();
    } else if (order instanceof SupplyRequest supplyRequest && supplyRequest.hasItemReference()) {
      itemRef = supplyRequest.getItemReference();
    }

    if (itemRef == null) {
      return null;
    }

    if (itemRef.getResource() instanceof Resource inlineResource) {
      return inlineResource;
    }

    if (!itemRef.hasReference()) {
      return null;
    }

    String reference = itemRef.getReference();

    if (reference.startsWith("#") && order instanceof DomainResource domainResource) {
      return FhirUtil.findInContained(reference.substring(1), Resource.class, domainResource);
    }

    try {
      org.hl7.fhir.r4.model.IdType idType = new org.hl7.fhir.r4.model.IdType(reference);
      String resourceType = idType.getResourceType();
      String idPart = idType.getIdPart();
      if (resourceType == null || idPart == null) {
        return null;
      }
      return (Resource) daoRegistry.getResourceDao(resourceType)
          .read(new org.hl7.fhir.r4.model.IdType(resourceType, idPart),
              new ca.uhn.fhir.rest.api.server.SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("Could not resolve item reference {} for order {}: {}", reference,
          order.getIdElement().toUnqualifiedVersionless().getValue(), e.getMessage());
      return null;
    }
  }

  private Bundle buildDataBundle(Patient patient, Coverage coverage, List<Resource> orders) {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(patient);
    bundle.addEntry().setResource(coverage);
    for (Resource order : orders) {
      bundle.addEntry().setResource(order);
    }

    // Include patient's clinical data from the payer's claims store.
    // CQL rules (e.g., PhysicalTherapyRule session counting) need historical
    // procedure data that the payer holds from adjudicated claims.
    String procedureSubjectRef = resolveProcedureSubjectReference(coverage, patient);
    includePatientClinicalData(bundle, procedureSubjectRef);

    return bundle;
  }

  /**
   * Queries the payer's JPA store for the patient's clinical data and adds it
   * to the evaluation bundle. This mirrors CDS Hooks prefetch behavior for DTR.
   */
  private void includePatientClinicalData(Bundle bundle, String patientSubjectRef) {
    if (patientSubjectRef == null || patientSubjectRef.isBlank()) {
      return;
    }

    try {
      var searchParams = new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
      searchParams.add("subject",
          new ca.uhn.fhir.rest.param.ReferenceParam(patientSubjectRef));
      var results = daoRegistry.getResourceDao(org.hl7.fhir.r4.model.Procedure.class)
          .search(searchParams, new ca.uhn.fhir.rest.api.server.SystemRequestDetails());

      for (var resource : results.getResources(0, results.size())) {
        bundle.addEntry().setResource((Resource) resource);
      }

      if (results.size() > 0) {
        logger.debug("Added {} Procedure resources for subject {} to evaluation bundle",
            results.size(), patientSubjectRef);
      }
    } catch (Exception e) {
      logger.debug("Could not query clinical data for subject {}: {}", patientSubjectRef, e.getMessage());
    }
  }

  private String resolveProcedureSubjectReference(Coverage coverage, Patient patient) {
    String beneficiaryRef =
        (coverage != null) ? toVersionlessPatientReference(coverage.getBeneficiary()) : null;
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
    if (reference == null || !reference.hasReference()) {
      return null;
    }

    String ref = reference.getReference();
    if (ref == null || ref.isBlank()) {
      return null;
    }

    var idType = reference.getReferenceElement();
    String resourceType = idType.getResourceType();
    String idPart = idType.getIdPart();
    if ("Patient".equals(resourceType) && idPart != null && !idPart.isBlank()) {
      String versionlessRef = idType.toVersionless().getValue();
      if (versionlessRef != null && !versionlessRef.isBlank()) {
        return versionlessRef;
      }
      return "Patient/" + idPart;
    }

    if (ref.startsWith("Patient/")) {
      return new org.hl7.fhir.r4.model.IdType(ref).toVersionless().getValue();
    }

    return null;
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
   * DTR only needs the raw questionnaire canonicals — no CDS enrichment (date, assertion ID, URL normalization).
   */
  private List<Extension> extractCoverageInfoExtensions(RequestGroup requestGroup) {
    List<Extension> coverageInfoExts = new ArrayList<>();
    if (requestGroup == null || !requestGroup.hasAction()) {
      return coverageInfoExts;
    }
    for (RequestGroup.RequestGroupActionComponent action : requestGroup.getAction()) {
      if (action == null) {
        continue;
      }
      Extension ext = action.getExtensionByUrl(FhirUtil.COVERAGE_INFO_EXT_URL);
      if (ext != null) {
        coverageInfoExts.add(ext);
      }
    }
    return coverageInfoExts;
  }
}
