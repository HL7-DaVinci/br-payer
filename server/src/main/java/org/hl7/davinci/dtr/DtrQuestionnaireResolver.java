package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.davinci.cdshooks.error.OperationOutcomeBuilder;
import org.hl7.davinci.common.CoverageInfoUtil;
import org.hl7.davinci.common.FhirUtil;
import org.hl7.davinci.common.FhirCodeExtractor;
import org.hl7.davinci.common.PayorIdentifierUtil;
import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.davinci.pas.PasConstants;
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
import org.hl7.fhir.r4.model.OperationOutcome;
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
  private final DtrContextRegistry contextRegistry;

  public DtrQuestionnaireResolver(DaoRegistry daoRegistry, PlanDefinitionService planDefinitionService,
      DtrContextRegistry contextRegistry) {
    this.daoRegistry = daoRegistry;
    this.planDefinitionService = planDefinitionService;
    this.contextRegistry = contextRegistry;
  }

  public enum ResolutionPath {
    QUESTIONNAIRE, ORDER, BOTH
  }

  /** How the launch that produced a package was discovered, used to scope intendedUse. */
  public enum DtrLaunchProvenance {
    PAS_TRN, CRD_CONTEXT, ORDER, EXPLICIT_QUESTIONNAIRE
  }

  public record ResolutionResult(List<ResolvedQuestionnaire> questionnaires, List<String> warnings) {
  }

  /**
   * Outcome of resolving a $questionnaire-package context id: the named
   * Questionnaire, how it was discovered, and the order/coverage recovered from
   * the pended prior authorization (PAS TRN) or the CRD context registry.
   */
  public record ContextResolution(
      Questionnaire questionnaire,
      List<CanonicalType> canonicals,
      DtrLaunchProvenance provenance,
      List<Resource> orders,
      Coverage coverage) {
  }

  /** Provenance metadata per resolved questionnaire */
  public record ResolvedQuestionnaire(
      String canonical,
      Questionnaire resource,
      ResolutionPath path,
      DtrLaunchProvenance provenance,
      List<String> sourceOrderIds,
      String warning) {
    /** Convenience constructor deriving launch provenance from the resolution path. */
    public ResolvedQuestionnaire(String canonical, Questionnaire resource, ResolutionPath path,
        List<String> sourceOrderIds, String warning) {
      this(canonical, resource, path, provenanceForPath(path), sourceOrderIds, warning);
    }

    private static DtrLaunchProvenance provenanceForPath(ResolutionPath path) {
      return path == ResolutionPath.ORDER ? DtrLaunchProvenance.ORDER
          : DtrLaunchProvenance.EXPLICIT_QUESTIONNAIRE;
    }

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
      return new ResolvedQuestionnaire(this.canonical, mergedResource, mergedPath, this.provenance,
          mergedOrderIds, mergedWarning);
    }

    /** Returns a copy with the launch provenance replaced (context-launch override). */
    public ResolvedQuestionnaire withProvenance(DtrLaunchProvenance newProvenance) {
      return new ResolvedQuestionnaire(this.canonical, this.resource, this.path, newProvenance,
          this.sourceOrderIds, this.warning);
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

  /**
   * Resolves a questionnaire context id to its Questionnaire. Retained for callers
   * that only need the Questionnaire; delegates to {@link #resolveContextLaunch}.
   */
  public Questionnaire resolveContext(String context) {
    return resolveContextLaunch(context).questionnaire();
  }

  /**
   * Resolves a $questionnaire-package context id, recovering the launch provenance
   * and the order/coverage the context refers to. Tries, in order: the PAS
   * item-trace-number trick (context = Questionnaire logical id), whose order and
   * coverage are recovered from the pended ClaimResponse and its stored Claim; then
   * the CRD coverage-assertion-id registry, whose order and coverage are read from
   * the stored {@link DtrContextRegistry.DtrContext}. A miss produces an
   * oper-8-conformant not-found OperationOutcome.
   */
  public ContextResolution resolveContextLaunch(String context) {
    // A PAS context is the unique trace number on a stored documentation CommunicationRequest;
    // the requested questionnaire is recorded on that request, never derived from the trace value.
    Questionnaire fromTrace = questionnaireForTraceNumber(context);
    if (fromTrace != null) {
      RecoveredOrder recovered = recoverOrderFromPas(context);
      return new ContextResolution(fromTrace, List.of(new CanonicalType(fromTrace.getUrl())),
          DtrLaunchProvenance.PAS_TRN, recovered.orders(), recovered.coverage());
    }

    ContextResolution fromRegistry = resolveViaContextRegistry(context);
    if (fromRegistry != null) {
      return fromRegistry;
    }

    String diagnostics = "No questionnaires are associated with context '" + context
        + "'. If this context came from a CRD coverage-information assertion, re-invoke CRD or"
        + " contact the payer at " + DtrConstants.PAYER_SUPPORT_CONTACT
        + "; documentation requirements may have changed.";
    throw new UnprocessableEntityException(diagnostics,
        OperationOutcomeBuilder.createOperationOutcome(
            OperationOutcome.IssueSeverity.ERROR,
            OperationOutcome.IssueType.NOTFOUND,
            null,
            diagnostics));
  }

  private ContextResolution resolveViaContextRegistry(String context) {
    var lookup = contextRegistry.lookup(context);
    if (lookup.isEmpty()) {
      return null;
    }
    DtrContextRegistry.DtrContext ctx = lookup.get();
    List<String> canonicals = ctx.questionnaireCanonicals();
    if (canonicals == null || canonicals.isEmpty()) {
      return null;
    }
    Questionnaire q = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonicals.get(0));
    if (q == null) {
      return null;
    }
    // A CRD context may name multiple questionnaires; carry the full list for union packaging.
    List<CanonicalType> canonicalTypes = new ArrayList<>();
    for (String canonical : canonicals) {
      if (canonical != null && !canonical.isBlank()) {
        canonicalTypes.add(new CanonicalType(canonical));
      }
    }
    List<Resource> orders = new ArrayList<>();
    if (ctx.order() != null) {
      orders.add(ctx.order().copy());
    }
    Coverage coverage = ctx.coverage() != null ? ctx.coverage().copy() : null;
    return new ContextResolution(q, canonicalTypes, DtrLaunchProvenance.CRD_CONTEXT, orders, coverage);
  }

  private record RecoveredOrder(List<Resource> orders, Coverage coverage) {
  }

  /**
   * Finds the documentation CommunicationRequest carrying the given trace number as its
   * identifier and resolves the questionnaire it requested.
   */
  private Questionnaire questionnaireForTraceNumber(String traceNumber) {
    ca.uhn.fhir.jpa.searchparam.SearchParameterMap params =
        new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
    params.setLoadSynchronous(true);
    params.add("identifier", new ca.uhn.fhir.rest.param.TokenParam(
        PasConstants.QUESTIONNAIRE_TRACE_NUMBER_SYSTEM, traceNumber));
    List<?> matches;
    try {
      matches = daoRegistry.getResourceDao(org.hl7.fhir.r4.model.CommunicationRequest.class)
          .searchForResources(params, new SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("Could not search CommunicationRequests for context {}: {}", traceNumber,
          e.getMessage());
      return null;
    }
    for (Object obj : matches) {
      var cr = (org.hl7.fhir.r4.model.CommunicationRequest) obj;
      Extension ext = cr.getExtensionByUrl(PasConstants.EXT_REQUESTED_QUESTIONNAIRE);
      if (ext != null && ext.getValue() instanceof CanonicalType canonical) {
        Questionnaire questionnaire =
            FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical.getValue());
        if (questionnaire != null) {
          return questionnaire;
        }
      }
    }
    return null;
  }

  /**
   * Recovers the ordered service and coverage behind a PAS questionnaire request.
   * The context is the item trace number carried on a pended ClaimResponse.item;
   * that ClaimResponse references the stored Claim, whose matching item carries the
   * requestedService order reference and whose insurance names the coverage.
   */
  private RecoveredOrder recoverOrderFromPas(String context) {
    List<Resource> orders = new ArrayList<>();
    Coverage coverage = null;

    ca.uhn.fhir.jpa.searchparam.SearchParameterMap params =
        new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
    params.setLoadSynchronous(true);
    List<?> responses;
    try {
      responses = daoRegistry.getResourceDao(org.hl7.fhir.r4.model.ClaimResponse.class)
          .searchForResources(params, new SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("Could not search ClaimResponses for context {}: {}", context, e.getMessage());
      return new RecoveredOrder(orders, null);
    }

    for (Object obj : responses) {
      org.hl7.fhir.r4.model.ClaimResponse cr = (org.hl7.fhir.r4.model.ClaimResponse) obj;
      Integer matchedSequence = matchItemTraceNumber(cr, context);
      if (matchedSequence == null) {
        continue;
      }
      org.hl7.fhir.r4.model.Claim claim = readClaim(cr.getRequest());
      if (claim == null) {
        continue;
      }
      Resource order = orderFromClaimItem(claim, matchedSequence);
      if (order != null) {
        orders.add(order);
      }
      coverage = coverageFromClaim(claim);
      break;
    }
    return new RecoveredOrder(orders, coverage);
  }

  private Integer matchItemTraceNumber(org.hl7.fhir.r4.model.ClaimResponse cr, String context) {
    for (org.hl7.fhir.r4.model.ClaimResponse.ItemComponent item : cr.getItem()) {
      for (Extension ext : item.getExtensionsByUrl(PasConstants.ITEM_TRACE_NUMBER)) {
        if (ext.getValue() instanceof Identifier id && context.equals(id.getValue())) {
          return item.getItemSequence();
        }
      }
    }
    return null;
  }

  private org.hl7.fhir.r4.model.Claim readClaim(Reference request) {
    if (request == null || !request.hasReference()) {
      return null;
    }
    try {
      IdType id = new IdType(request.getReference());
      return daoRegistry.getResourceDao(org.hl7.fhir.r4.model.Claim.class)
          .read(new IdType("Claim", id.getIdPart()), new SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("Could not read Claim {}: {}", request.getReference(), e.getMessage());
      return null;
    }
  }

  private Resource orderFromClaimItem(org.hl7.fhir.r4.model.Claim claim, int sequence) {
    for (org.hl7.fhir.r4.model.Claim.ItemComponent item : claim.getItem()) {
      if (item.getSequence() != sequence) {
        continue;
      }
      Extension requested = item.getExtensionByUrl(PasConstants.ITEM_REQUESTED_SERVICE);
      if (requested != null && requested.getValue() instanceof Reference ref) {
        return readReference(ref);
      }
    }
    return null;
  }

  private Coverage coverageFromClaim(org.hl7.fhir.r4.model.Claim claim) {
    if (!claim.hasInsurance()) {
      return null;
    }
    Reference coverageRef = claim.getInsuranceFirstRep().getCoverage();
    return (Coverage) readReference(coverageRef);
  }

  /**
   * Reads a reference from this server's store. Only valid for payer-local
   * references, i.e. those taken from resources this server persisted itself
   * (the PAS-stored Claim and its rewritten references).
   */
  private Resource readReference(Reference ref) {
    if (ref == null || !ref.hasReference()) {
      return null;
    }
    try {
      IdType id = new IdType(ref.getReference());
      String type = id.getResourceType();
      if (type == null || type.isBlank()) {
        return null;
      }
      return (Resource) daoRegistry.getResourceDao(type)
          .read(new IdType(type, id.getIdPart()), new SystemRequestDetails());
    } catch (Exception e) {
      logger.debug("Could not read referenced resource {}: {}", ref.getReference(), e.getMessage());
      return null;
    }
  }

  private void resolveViaOrders(List<Resource> validOrders, Coverage coverage,
      Map<String, ResolvedQuestionnaire> results, List<String> warnings) {

    // Resolve Patient from coverage beneficiary
    MemberResolution member = resolvePatient(coverage);
    if (member == null) {
      String warning = "Could not resolve patient from Coverage beneficiary; skipping order-based resolution";
      logger.warn(warning);
      warnings.add(warning);
      return;
    }
    Patient patient = member.patient();
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
    // A payer-local member's own id scopes local clinical-data searches; otherwise the
    // beneficiary reference is kept as-is.
    String subjectRef = member.payerLocal()
        ? patient.getIdElement().toVersionless().getValue()
        : resolveSubjectReference(coverage, patient);
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

  private record MemberResolution(Patient patient, boolean payerLocal) {
  }

  /**
   * Resolves the member Patient for a Coverage that arrived from the provider.
   * The beneficiary reference carries a provider logical id that must never be
   * read against this server's store; the member is located by inline/contained
   * resource or by member identifier, falling back to a stub so PlanDefinition
   * evaluation has a subject context. payerLocal marks a member found in this
   * server's store, whose id may be used for local clinical-data searches.
   */
  private MemberResolution resolvePatient(Coverage coverage) {
    if (coverage == null) {
      return null;
    }

    Reference beneficiary = coverage.hasBeneficiary() ? coverage.getBeneficiary() : null;
    Patient supplied = beneficiary != null
        ? ResourceResolver.resolveTypedReferenceFromDao(beneficiary, Patient.class, coverage, null)
        : null;
    if (supplied != null) {
      return new MemberResolution(supplied, false);
    }

    Patient member = findPatientByMemberIdentifier(coverage);
    if (member != null) {
      return new MemberResolution(member, true);
    }

    String patientRef = beneficiary != null ? beneficiary.getReference() : null;
    if (patientRef == null) {
      return null;
    }
    String idPart = new org.hl7.fhir.r4.model.IdType(patientRef).getIdPart();
    logger.debug("No member Patient resolvable for coverage; using stub {} for PlanDefinition evaluation",
        idPart);
    Patient stub = new Patient();
    stub.setId(idPart);
    return new MemberResolution(stub, false);
  }

  /**
   * Searches this server's Patients by the coverage's member identifier values
   * (Coverage.identifier and subscriberId), matching on identifier value across
   * systems since the provider and payer may scope them differently.
   */
  private Patient findPatientByMemberIdentifier(Coverage coverage) {
    List<String> candidateValues = new ArrayList<>();
    for (Identifier identifier : coverage.getIdentifier()) {
      if (identifier.hasValue()) {
        candidateValues.add(identifier.getValue());
      }
    }
    if (coverage.hasSubscriberId() && !candidateValues.contains(coverage.getSubscriberId())) {
      candidateValues.add(coverage.getSubscriberId());
    }

    for (String value : candidateValues) {
      ca.uhn.fhir.jpa.searchparam.SearchParameterMap params =
          new ca.uhn.fhir.jpa.searchparam.SearchParameterMap();
      params.setLoadSynchronous(true);
      params.add("identifier", new ca.uhn.fhir.rest.param.TokenParam(null, value));
      params.setCount(1);
      Patient match = daoRegistry.getResourceDao(Patient.class)
          .searchForResources(params, new ca.uhn.fhir.rest.api.server.SystemRequestDetails())
          .stream()
          .findFirst()
          .orElse(null);
      if (match != null) {
        return match;
      }
    }
    return null;
  }

  private List<Identifier> extractPayorIdentifiers(Coverage coverage) {
    List<Identifier> identifiers = new ArrayList<>();
    if (coverage == null || !coverage.hasPayor()) {
      return identifiers;
    }
    // Payor references carry provider logical ids; only inline/contained payor
    // Organizations are usable here, never a local read by that id.
    for (Reference payorRef : coverage.getPayor()) {
      Organization org = ResourceResolver.resolveTypedReferenceFromDao(
          payorRef, Organization.class, coverage, null);
      if (org != null) {
        PayorIdentifierUtil.addValidIdentifiers(identifiers, org);
      }
    }
    return identifiers;
  }

  private Resource resolveItemReference(Resource order) {
    // Orders arrive by value from the provider; their internal references only
    // resolve inline/contained, never against this server's store by id.
    return FhirCodeExtractor.resolveReferencedItem(order, itemRef -> {
      DomainResource parent = order instanceof DomainResource domainResource ? domainResource : null;
      return ResourceResolver.resolveReferenceFromDao(itemRef, parent, null);
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
