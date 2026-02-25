package org.hl7.davinci.cdshooks.shared;

import static org.hl7.davinci.common.FhirConstants.CARD_TYPE_SYSTEM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.cdshooks.CdsHooksTestUtils;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.RelatedArtifact;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.junit.jupiter.api.Test;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseCardJson;

class CardConverterConvertToCardsTest {

  private final CardConverter cardConverter = new CardConverter();

  @Test
  void convertToCards_buildsCardWhenActionMatchesHookAndCardType() {
    RequestGroup requestGroup = new RequestGroup();
    RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
    action.setId("action-1");
    action.setTitle("Coverage guidance");
    action.setDescription("Authorization may be required");
    action.addCode(code("insurance"));

    PlanDefinition plan = planDefinition("action-1", "order-sign");
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");
    ResolvedResources context = contextWithPayor();

    List<CdsServiceResponseCardJson> cards =
        cardConverter.convertToCards(requestGroup, plan, order, context, "order-sign");

    assertEquals(1, cards.size());
    CdsServiceResponseCardJson card = cards.get(0);
    assertEquals("Coverage guidance", card.getSummary());
    assertEquals("insurance", card.getSource().getTopic().getCode());
    assertEquals("Example Payer", card.getSource().getLabel());
    assertTrue(card.getExtension() instanceof CrdCardExtension);
    assertTrue(((CrdCardExtension) card.getExtension()).getAssociatedResources().contains("DeviceRequest/dr-1"));
  }

  @Test
  void convertToCards_skipsActionWhenTriggerDoesNotMatchHook() {
    RequestGroup requestGroup = new RequestGroup();
    RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
    action.setId("action-1");
    action.setTitle("Coverage guidance");
    action.addCode(code("insurance"));

    PlanDefinition plan = planDefinition("action-1", "appointment-book");
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");

    List<CdsServiceResponseCardJson> cards =
        cardConverter.convertToCards(requestGroup, plan, order, new ResolvedResources(), "order-sign");

    assertTrue(cards.isEmpty());
  }

  @Test
  void convertToCards_skipsCoverageInfoTopicBecauseItIsSystemActionOnly() {
    RequestGroup requestGroup = new RequestGroup();
    RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
    action.setId("action-1");
    action.setTitle("Coverage info");
    action.addCode(code("coverage-info"));

    PlanDefinition plan = planDefinition("action-1", "order-sign");
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");

    List<CdsServiceResponseCardJson> cards =
        cardConverter.convertToCards(requestGroup, plan, order, new ResolvedResources(), "order-sign");

    assertTrue(cards.isEmpty());
  }

  @Test
  void convertToCards_mapsDocumentationLinksWithSmartType() {
    RequestGroup requestGroup = new RequestGroup();
    RequestGroup.RequestGroupActionComponent action = requestGroup.addAction();
    action.setId("action-1");
    action.setTitle("Coverage guidance");
    action.addCode(code("insurance"));

    RelatedArtifact smartLink = new RelatedArtifact();
    smartLink.setUrl("http://example.org/smart");
    smartLink.setDisplay("Launch app");
    smartLink.addExtension(new Extension(
        "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/linkType",
        new CodeType("smart")));
    action.addDocumentation(smartLink);

    PlanDefinition plan = planDefinition("action-1", "order-sign");
    DeviceRequest order = CdsHooksTestUtils.createTestDeviceRequest("dr-1", "E0424", "patient1");

    List<CdsServiceResponseCardJson> cards =
        cardConverter.convertToCards(requestGroup, plan, order, new ResolvedResources(), "order-sign");

    assertEquals(1, cards.size());
    assertNotNull(cards.get(0).getLinks());
    assertEquals(1, cards.get(0).getLinks().size());
    assertEquals("smart", cards.get(0).getLinks().get(0).getType());
  }

  private PlanDefinition planDefinition(String actionId, String hookName) {
    PlanDefinition plan = new PlanDefinition();
    plan.setUrl("http://example.org/PlanDefinition/test");
    plan.setPublisher("Da Vinci");
    PlanDefinition.PlanDefinitionActionComponent action = plan.addAction();
    action.setId(actionId);
    action.addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName(hookName);
    return plan;
  }

  private CodeableConcept code(String topicCode) {
    CodeableConcept concept = new CodeableConcept();
    concept.addCoding().setSystem(CARD_TYPE_SYSTEM).setCode(topicCode);
    return concept;
  }

  private ResolvedResources contextWithPayor() {
    ResolvedResources context = new ResolvedResources();

    Organization payer = new Organization();
    payer.setId("org-1");
    payer.setName("Example Payer");
    context.setOrganizations(List.of(payer));

    org.hl7.fhir.r4.model.Coverage coverage = new org.hl7.fhir.r4.model.Coverage();
    coverage.setId("cov-1");
    coverage.addPayor().setReference("Organization/org-1");
    context.setCoverage(coverage);

    return context;
  }
}
