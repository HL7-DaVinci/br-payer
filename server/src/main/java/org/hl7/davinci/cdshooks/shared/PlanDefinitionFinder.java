package org.hl7.davinci.cdshooks.shared;

import java.util.List;

import org.hl7.davinci.common.PlanDefinitionService;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

/**
 * CDS Hooks-specific PlanDefinition execution. Converts $apply results into
 * CDS Hooks cards and system actions.
 *
 * @see PlanDefinitionService for general PlanDefinition search and $apply
 */
@Component
public class PlanDefinitionFinder {

  @Autowired
  private PlanDefinitionService planDefinitionService;

  @Autowired
  private CardConverter cardConverter;

  @Autowired
  private CoverageInfoHandler coverageInfoHandler;

  /**
   * Executes a PlanDefinition and returns response with cards and system actions.
   *
   * @param plan            the PlanDefinition to execute
   * @param context         the resolved resources from the CDS request
   * @param contextResource the specific resource being processed
   * @param request         the original CDS request
   * @param hookName        the hook name (e.g., "order-sign")
   * @param dataBundle      the pre-built data bundle for the CQL engine
   */
  public CdsServiceResponseJson applyForCdsResponse(PlanDefinition plan, ResolvedResources context,
      Resource contextResource, CdsServiceRequestJson request, String hookName, Bundle dataBundle) {

    CdsServiceResponseJson planResponse = new CdsServiceResponseJson();

    Parameters cqlParameters = new Parameters();
    cqlParameters.addParameter("Hook", new StringType(hookName));

    String patientId = context.getPatient().getIdElement().getIdPart();
    RequestGroup requestGroup = planDefinitionService.applyPlanDefinition(plan, patientId, dataBundle, cqlParameters);

    List<CdsServiceResponseCardJson> cards = cardConverter.convertToCards(requestGroup, plan, contextResource, context,
        hookName);
    cards.forEach(planResponse::addCard);

    Extension coverageInfoExt = coverageInfoHandler.extractCoverageExtension(requestGroup, context.getCoverage());

    if (coverageInfoExt != null) {
      CdsServiceResponseSystemActionJson systemAction = coverageInfoHandler.buildCoverageInfoSystemAction(
          contextResource, coverageInfoExt);
      if (systemAction != null) {
        planResponse.addServiceAction(systemAction);
      }
    }

    return planResponse;
  }
}
