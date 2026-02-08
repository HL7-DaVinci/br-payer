package org.hl7.davinci.common;

import java.util.ArrayList;
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
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
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
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.CompositeAndListParam;
import ca.uhn.fhir.rest.param.CompositeOrListParam;
import ca.uhn.fhir.rest.param.CompositeParam;
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

    // Order code search - include both http and https variants for protocol-agnostic matching
    CompositeAndListParam<TokenParam, TokenParam> orderCodeParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    CompositeOrListParam<TokenParam, TokenParam> codeOrList = new CompositeOrListParam<>(TokenParam.class,
        TokenParam.class);
    codeOrList.addOr(new CompositeParam<>(
        new TokenParam("focus"),
        new TokenParam(code.getSystem(), code.getCode())));

    if (code.hasSystem()) {
      String altSystem = FhirUtil.getAlternateProtocolUrl(code.getSystem());
      if (altSystem != null) {
        codeOrList.addOr(new CompositeParam<>(
            new TokenParam("focus"),
            new TokenParam(altSystem, code.getCode())));
      }
    }

    orderCodeParam.addAnd(codeOrList);
    searchParams.add("context-type-value", orderCodeParam);

    // Payor identifiers search
    CompositeAndListParam<TokenParam, TokenParam> payorIdentifiersParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    CompositeOrListParam<TokenParam, TokenParam> payorOrList = new CompositeOrListParam<>(TokenParam.class,
        TokenParam.class);
    for (Identifier payorId : payorIdentifiers) {
      payorOrList.addOr(new CompositeParam<>(
          new TokenParam("program"),
          new TokenParam(payorId.getSystem(), payorId.getValue())));
    }
    payorIdentifiersParam.addAnd(payorOrList);
    searchParams.add("context-type-value", payorIdentifiersParam);

    IBundleProvider planDefBundle = daoRegistry
        .getResourceDao(PlanDefinition.class)
        .search(searchParams, new SystemRequestDetails());

    planDefBundle.getResources(0, planDefBundle.size()).forEach(resource -> {
      if (resource instanceof PlanDefinition planDef) {
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

    CompositeAndListParam<TokenParam, TokenParam> payorIdentifiersParam = new CompositeAndListParam<>(TokenParam.class,
        TokenParam.class);
    CompositeOrListParam<TokenParam, TokenParam> payorOrList = new CompositeOrListParam<>(TokenParam.class,
        TokenParam.class);
    for (Identifier payorId : payorIdentifiers) {
      payorOrList.addOr(new CompositeParam<>(
          new TokenParam("program"),
          new TokenParam(payorId.getSystem(), payorId.getValue())));
    }
    payorIdentifiersParam.addAnd(payorOrList);
    searchParams.add("context-type-value", payorIdentifiersParam);

    IBundleProvider result = daoRegistry
        .getResourceDao(PlanDefinition.class)
        .search(searchParams, new SystemRequestDetails());

    return !result.isEmpty();
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
          .map(ref -> FhirUtil.findInContained(ref.substring(1), RequestGroup.class, carePlan))
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
