package org.hl7.davinci.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;

class ScenarioMetadataProviderTest {

  private DaoRegistry daoRegistry;
  private IFhirResourceDao<Questionnaire> questionnaireDao;
  private IFhirResourceDao<PlanDefinition> planDefinitionDao;
  private ScenarioMetadataProvider provider;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    daoRegistry = mock(DaoRegistry.class);
    questionnaireDao = mock(IFhirResourceDao.class);
    planDefinitionDao = mock(IFhirResourceDao.class);
    when(daoRegistry.getResourceDao(Questionnaire.class)).thenReturn(questionnaireDao);
    when(daoRegistry.getResourceDao(PlanDefinition.class)).thenReturn(planDefinitionDao);
    provider = new ScenarioMetadataProvider(daoRegistry);
  }

  @Test
  void fetchAll_returnsAllResources() {
    Questionnaire q1 = questionnaire("QuestionnaireA");
    Questionnaire q2 = questionnaire("QuestionnaireB");
    when(questionnaireDao.searchForResources(any(), any())).thenReturn(List.of(q1, q2));

    List<Questionnaire> questionnaires = provider.fetchAll(Questionnaire.class);

    assertEquals(2, questionnaires.size());
  }

  @Test
  void getMetadata_scansQuestionnairesAndPlanDefinitionsFromDao() {
    Questionnaire questionnaire = questionnaire("HomeOxygenTherapyForm");
    questionnaire.setUrl("http://example.org/fhir/Questionnaire/HomeOxygenTherapyForm");
    PlanDefinition plan = new PlanDefinition();
    plan.setName("HomeOxygenTherapy");
    plan.setTitle("Home Oxygen Therapy");
    plan.addAction()
        .addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-sign");

    when(questionnaireDao.searchForResources(any(), any())).thenReturn(List.of(questionnaire));
    when(planDefinitionDao.searchForResources(any(), any())).thenReturn(List.of(plan));

    List<ScenarioMetadata> metadata = provider.getMetadata();

    assertFalse(metadata.isEmpty());
    assertEquals("home-oxygen-therapy", metadata.get(0).id());
  }

  private Questionnaire questionnaire(String name) {
    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setName(name);
    return questionnaire;
  }
}
