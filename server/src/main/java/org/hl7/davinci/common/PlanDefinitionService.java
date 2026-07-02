package org.hl7.davinci.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseBackboneElement;
import org.hl7.fhir.instance.model.api.IBaseDatatype;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CarePlan;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.hl7.fhir.r4.model.TriggerDefinition.TriggerType;
import org.opencds.cqf.fhir.cr.hapi.common.IPlanDefinitionProcessorFactory;
import org.opencds.cqf.fhir.cr.plandefinition.PlanDefinitionProcessor;
import org.opencds.cqf.fhir.utility.monad.Eithers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * General PlanDefinition search and execution operations.
 * Contains no CDS Hooks-specific dependencies.
 */
@Component
public class PlanDefinitionService {

  private static final Logger logger = LoggerFactory.getLogger(PlanDefinitionService.class);

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

  /**
   * Finds PlanDefinitions based on the provided code, payor identifiers, and hook.
   */
  public List<PlanDefinition> findPlanDefinitions(Coding code, List<Identifier> payorIdentifiers, String hook) {

    logger.info("Finding PlanDefinitions for code: {}|{}, payorIdentifiers: {}, hook: {}", code.getSystem(),
        code.getCode(), payorIdentifiers.stream().map(i -> i.getSystem() + "|" + i.getValue()).toList(), hook);

    List<PlanDefinition> plans = new ArrayList<>();

    SearchParameterMap searchParams = new SearchParameterMap();

    // Use context-type + context token params instead of context-type-value composite.
    // This preserves OR matching while avoiding a known hang in composite OR searches.
    TokenAndListParam contextTypeParam = new TokenAndListParam();
    contextTypeParam.addAnd(new TokenOrListParam().addOr(new TokenParam("focus")));
    contextTypeParam.addAnd(new TokenOrListParam().addOr(new TokenParam("program")));
    searchParams.add("context-type", contextTypeParam);

    TokenAndListParam contextParam = new TokenAndListParam();
    TokenOrListParam codeOrList = new TokenOrListParam();
    codeOrList.addOr(new TokenParam(code.getSystem(), code.getCode()));
    if (code.hasSystem()) {
      String altSystem = FhirUtil.getAlternateProtocolUrl(code.getSystem());
      if (altSystem != null) {
        codeOrList.addOr(new TokenParam(altSystem, code.getCode()));
      }
    }
    contextParam.addAnd(codeOrList);

    // Payor identifiers OR search
    TokenOrListParam payorOrList = new TokenOrListParam();
    for (Identifier payorId : payorIdentifiers) {
      payorOrList.addOr(new TokenParam(payorId.getSystem(), payorId.getValue()));
    }
    contextParam.addAnd(payorOrList);
    searchParams.add("context", contextParam);

    logger.info("Constructed search parameters: {}", searchParams.toNormalizedQueryString());

    List<PlanDefinition> planDefs = daoRegistry
        .getResourceDao(PlanDefinition.class)
        .searchForResources(searchParams, new SystemRequestDetails());

    planDefs.forEach(planDef -> {
      if (hook == null) {
        // DTR path: no trigger filtering, accept all code+payor matches
        plans.add(planDef);
        return;
      }
      if (planDef.hasAction()) {
        for (PlanDefinition.PlanDefinitionActionComponent action : planDef.getAction()) {
          if (action.hasTrigger()) {
            boolean hasMatchingTrigger = action.getTrigger().stream()
                .anyMatch(trigger -> trigger.hasType() && trigger.getType() == TriggerType.NAMEDEVENT
                    && trigger.getName().equals(hook));
            if (hasMatchingTrigger) {
              plans.add(planDef);
              break;
            }
          }
        }
      }
    });

    logger.info("Found {} PlanDefinitions for codes", plans.size());
    return plans;
  }

  /**
   * Checks if any PlanDefinition exists for the given payor identifiers.
   */
  public boolean isPayorHandled(List<Identifier> payorIdentifiers) {
    SearchParameterMap searchParams = new SearchParameterMap();
    searchParams.setCount(1);

    TokenAndListParam contextTypeParam = new TokenAndListParam();
    contextTypeParam.addAnd(new TokenOrListParam().addOr(new TokenParam("program")));
    searchParams.add("context-type", contextTypeParam);

    TokenAndListParam contextParam = new TokenAndListParam();
    TokenOrListParam payorOrList = new TokenOrListParam();
    for (Identifier payorId : payorIdentifiers) {
      payorOrList.addOr(new TokenParam(payorId.getSystem(), payorId.getValue()));
    }
    contextParam.addAnd(payorOrList);
    searchParams.add("context", contextParam);

    return !daoRegistry.getResourceDao(PlanDefinition.class)
        .searchForIds(searchParams, new SystemRequestDetails())
        .isEmpty();
  }

  /**
   * Removes plans that apply exclusively to the order-dispatch stage unless the
   * order shows evidence of having reached dispatch: a non-draft status and a
   * named performer (or supplier). A null order provides no evidence, so
   * dispatch-only plans are always removed. Returns one note per removed plan.
   */
  public List<String> removeDispatchPlansLackingEvidence(Collection<PlanDefinition> plans, Resource order) {
    if (order != null && !isDraftOrder(order) && hasPerformer(order)) {
      return List.of();
    }
    String orderLabel = order != null
        ? order.getIdElement().toUnqualifiedVersionless().getValue()
        : "(no order resource)";
    List<String> notes = new ArrayList<>();
    plans.removeIf(plan -> {
      if (!isDispatchOnly(plan)) {
        return false;
      }
      notes.add("Excluding PlanDefinition " + plan.getIdElement().getIdPart()
          + " for order " + orderLabel
          + ": no evidence the order-dispatch stage has been reached"
          + " (order must be non-draft and name a performer)");
      return true;
    });
    return notes;
  }

  private static boolean isDraftOrder(Resource order) {
    Property status = order.getChildByName("status");
    return status != null && !status.getValues().isEmpty()
        && "draft".equals(status.getValues().get(0).primitiveValue());
  }

  /**
   * True when the order names a fulfilling party: performer, or supplier for
   * SupplyRequest. Iterates children because getChildByName throws for
   * properties a resource type does not define.
   */
  private static boolean hasPerformer(Resource order) {
    for (Property property : order.children()) {
      String name = property.getName();
      if (("performer".equals(name) || "supplier".equals(name))
          && !property.getValues().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when every named-event trigger on the plan is order-dispatch.
   * Plans without named-event triggers are not considered dispatch-only.
   */
  private static boolean isDispatchOnly(PlanDefinition plan) {
    boolean sawNamedEvent = false;
    for (PlanDefinition.PlanDefinitionActionComponent action : plan.getAction()) {
      for (TriggerDefinition trigger : action.getTrigger()) {
        if (trigger.hasType() && trigger.getType() == TriggerType.NAMEDEVENT) {
          sawNamedEvent = true;
          if (!"order-dispatch".equals(trigger.getName())) {
            return false;
          }
        }
      }
    }
    return sawNamedEvent;
  }

  /**
   * Runs PlanDefinition $apply and extracts the resulting RequestGroup.
   *
   * @param plan          the PlanDefinition to execute
   * @param patientId     the patient ID for subject context
   * @param dataBundle    the data bundle for CQL evaluation
   * @param cqlParameters additional CQL parameters (e.g., Hook name), or null
   * @return the RequestGroup from the $apply result, or null
   */
  public RequestGroup applyPlanDefinition(PlanDefinition plan, String patientId,
      Bundle dataBundle, Parameters cqlParameters) {

    PlanDefinitionProcessor processor = planDefinitionProcessorFactory.create(new SystemRequestDetails());

    IBaseResource result = processor.applyR5(
        Eithers.forMiddle3(plan.getIdElement().toUnqualifiedVersionless()),
        List.of(patientId),
        (String) null,
        (String) null,
        (String) null,
        (IBaseDatatype) null,
        (IBaseDatatype) null,
        (IBaseDatatype) null,
        (IBaseDatatype) null,
        (IBaseDatatype) null,
        cqlParameters,
        false,
        dataBundle,
        (List<? extends IBaseBackboneElement>) null,
        (IBaseResource) null,
        (IBaseResource) null,
        (IBaseResource) null);

    RequestGroup rg = extractRequestGroup(result);
    if (rg != null) {
      // Strip null actions produced by applyR5 when conditions evaluate to false/null
      // (known deficiency in cqf-fhir-cr: ProcessAction returns null for non-applicable actions)
      rg.getAction().removeIf(java.util.Objects::isNull);
    }
    return rg;
  }

  /**
   * Extracts the RequestGroup from the $apply result.
   * Handles both R4 CarePlan (with contained RequestGroup) and R5 Parameters response formats.
   */
  public RequestGroup extractRequestGroup(IBaseResource resource) {

    // R4 $apply returns a CarePlan with contained RequestGroup
    if (resource instanceof CarePlan carePlan) {
      if (!carePlan.hasActivity() || !carePlan.hasContained()) {
        return null;
      }
      return carePlan.getActivity().stream()
          .filter(CarePlan.CarePlanActivityComponent::hasReference)
          .map(activity -> activity.getReference().getReference())
          .filter(ref -> ref != null && ref.startsWith("#"))
          .map(carePlan::getContained)
          .filter(RequestGroup.class::isInstance)
          .map(RequestGroup.class::cast)
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(null);
    }

    // R5 $apply returns a Parameters resource with RequestGroup in the "return" parameter
    if (resource instanceof Parameters params) {
      Parameters.ParametersParameterComponent returnParam = params.getParameter("return");
      if (returnParam == null) {
        return null;
      }
      Resource returnResource = (Resource) returnParam.getResource();
      if (returnResource instanceof Bundle bundle) {
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
          if (entry.getResource() instanceof RequestGroup rg) {
            return rg;
          }
        }
      } else if (returnResource instanceof RequestGroup rg) {
        return rg;
      }
    }

    return null;
  }
}
