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
import ca.uhn.fhir.rest.api.server.IBundleProvider;

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
  void fetchAll_withKnownTotalUsesSingleBatch() {
    Questionnaire q1 = questionnaire("QuestionnaireA");
    Questionnaire q2 = questionnaire("QuestionnaireB");
    IBundleProvider results = mock(IBundleProvider.class);
    when(results.size()).thenReturn(2);
    when(results.getResources(0, 2)).thenReturn(List.of(q1, q2));
    when(questionnaireDao.search(any(), any())).thenReturn(results);

    List<Questionnaire> questionnaires = provider.fetchAll(Questionnaire.class);

    assertEquals(2, questionnaires.size());
  }

  @Test
  void fetchAll_withUnknownTotalPaginatesUntilEmptyBatch() {
    Questionnaire q1 = questionnaire("QuestionnaireA");
    IBundleProvider results = mock(IBundleProvider.class);
    when(results.size()).thenReturn(null);
    when(results.getResources(0, 200)).thenReturn(List.of(q1));
    when(results.getResources(1, 201)).thenReturn(List.of());
    when(questionnaireDao.search(any(), any())).thenReturn(results);

    List<Questionnaire> questionnaires = provider.fetchAll(Questionnaire.class);

    assertEquals(1, questionnaires.size());
    assertEquals("QuestionnaireA", questionnaires.get(0).getName());
  }

  @Test
  void getMetadata_scansQuestionnairesAndPlanDefinitionsFromDao() {
    Questionnaire questionnaire = questionnaire("HomeOxygenTherapyForm");
    questionnaire.setUrl("http://hl7.org/fhir/us/davinci-dtr/Questionnaire/HomeOxygenTherapyForm");
    PlanDefinition plan = new PlanDefinition();
    plan.setName("HomeOxygenTherapy");
    plan.setTitle("Home Oxygen Therapy");
    plan.addAction()
        .addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-sign");

    IBundleProvider qResults = mock(IBundleProvider.class);
    when(qResults.size()).thenReturn(1);
    when(qResults.getResources(0, 1)).thenReturn(List.of(questionnaire));
    when(questionnaireDao.search(any(), any())).thenReturn(qResults);

    IBundleProvider pdResults = mock(IBundleProvider.class);
    when(pdResults.size()).thenReturn(1);
    when(pdResults.getResources(0, 1)).thenReturn(List.of(plan));
    when(planDefinitionDao.search(any(), any())).thenReturn(pdResults);

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
