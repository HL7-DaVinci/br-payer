package org.hl7.davinci.scenarios;

import static org.hl7.davinci.common.FhirConstants.HCPCS_SYSTEM;
import static org.hl7.davinci.common.FhirConstants.USAGE_CONTEXT_TYPE_SYSTEM;
import static org.hl7.davinci.dtr.DtrConstants.DTR_QUESTIONNAIRE_PREFIX;
import static org.hl7.davinci.dtr.DtrConstants.Q_ADAPT_PROFILE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.TriggerDefinition;
import org.hl7.fhir.r4.model.UsageContext;
import org.junit.jupiter.api.Test;

class LibraryScenarioScannerTest {

  @Test
  void findMatchingPlanDefinition_prefersLongestPrefix() {
    PlanDefinition shortPrefix = new PlanDefinition();
    shortPrefix.setName("Opioid");

    PlanDefinition longPrefix = new PlanDefinition();
    longPrefix.setName("OpioidPrescribing");

    PlanDefinition matched = LibraryScenarioScanner.findMatchingPlanDefinition(
        "OpioidPrescribingJustification",
        List.of(shortPrefix, longPrefix));

    assertNotNull(matched);
    assertEquals("OpioidPrescribing", matched.getName());
  }

  @Test
  void scan_buildsScenarioFromPlanDefinitionAndQuestionnaire() {
    PlanDefinition plan = new PlanDefinition();
    plan.setName("HomeOxygenTherapy");
    plan.setTitle("Home Oxygen Therapy");

    UsageContext focusContext = new UsageContext();
    focusContext.getCode().setSystem(USAGE_CONTEXT_TYPE_SYSTEM).setCode("focus");
    focusContext.setValue(new CodeableConcept().addCoding(
        new Coding(HCPCS_SYSTEM, "E0424", "Stationary Oxygen")));
    plan.addUseContext(focusContext);

    UsageContext taskContext = new UsageContext();
    taskContext.getCode().setSystem(USAGE_CONTEXT_TYPE_SYSTEM).setCode("task");
    taskContext.setValue(new CodeableConcept().setText("ServiceRequest"));
    plan.addUseContext(taskContext);

    PlanDefinition.PlanDefinitionActionComponent action = plan.addAction();
    action.addTrigger()
        .setType(TriggerDefinition.TriggerType.NAMEDEVENT)
        .setName("order-select");

    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setName("HomeOxygenTherapyQuestionnaire");
    questionnaire.setTitle("Home Oxygen Questionnaire");
    questionnaire.setUrl(DTR_QUESTIONNAIRE_PREFIX + "HomeOxygenTherapyQuestionnaire");
    questionnaire.getMeta().addProfile(Q_ADAPT_PROFILE);
    questionnaire.addItem().setLinkId("1").setText("First question");

    List<ScenarioMetadata> scenarios = LibraryScenarioScanner.scan(
        List.of(questionnaire),
        List.of(plan));

    assertEquals(1, scenarios.size());
    ScenarioMetadata scenario = scenarios.get(0);
    assertEquals("home-oxygen-therapy", scenario.id());
    assertEquals("Home Oxygen Therapy", scenario.name());
    assertEquals("ServiceRequest", scenario.orderType());
    assertEquals(List.of("order-select"), scenario.hookTriggers());
    assertEquals(List.of(questionnaire.getUrl()), scenario.questionnaireUrls());
    assertTrue(scenario.isAdaptive());
    assertTrue(scenario.hasInitialItems());
    assertFalse(scenario.focusCodes().isEmpty());
    assertEquals("E0424", scenario.focusCodes().get(0).getCode());
  }

  @Test
  void scan_skipsPatientInfoSubQuestionnaireAndCreatesOrphanScenario() {
    Questionnaire patientInfo = new Questionnaire();
    patientInfo.setName("PatientInfo");
    patientInfo.setUrl(DTR_QUESTIONNAIRE_PREFIX + "PatientInfo");

    Questionnaire orphan = new Questionnaire();
    orphan.setName("StandaloneForm");
    orphan.setTitle("Standalone Form");
    orphan.setUrl(DTR_QUESTIONNAIRE_PREFIX + "StandaloneForm");

    List<ScenarioMetadata> scenarios = LibraryScenarioScanner.scan(
        List.of(patientInfo, orphan),
        List.of());

    assertEquals(1, scenarios.size());
    assertEquals("standalone-form", scenarios.get(0).id());
    assertEquals(List.of(orphan.getUrl()), scenarios.get(0).questionnaireUrls());
  }
}
