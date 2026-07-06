package org.hl7.davinci.scenarios.cdex;

import static org.junit.jupiter.api.Assertions.*;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.junit.jupiter.api.Test;

class QuestionnaireResponseGeneratorTest {

  @Test
  void generatesCompletedResponseWithAnswerForEveryQuestion() {
    Questionnaire questionnaire = new Questionnaire();
    questionnaire.setUrl("http://example.org/Questionnaire/HomeOxygenTherapy");
    questionnaire.addItem().setLinkId("1").setText("Free text").setType(QuestionnaireItemType.STRING);
    questionnaire.addItem().setLinkId("2").setType(QuestionnaireItemType.BOOLEAN);
    Questionnaire.QuestionnaireItemComponent choice = questionnaire.addItem()
        .setLinkId("3").setType(QuestionnaireItemType.CHOICE);
    choice.addAnswerOption().setValue(new Coding("http://loinc.org", "LA33-6", "Yes"));
    Questionnaire.QuestionnaireItemComponent group = questionnaire.addItem()
        .setLinkId("4").setType(QuestionnaireItemType.GROUP);
    group.addItem().setLinkId("4.1").setType(QuestionnaireItemType.INTEGER);
    questionnaire.addItem().setLinkId("5").setType(QuestionnaireItemType.DISPLAY);

    QuestionnaireResponse qr = QuestionnaireResponseGenerator.generate(questionnaire, "Patient/p1");

    assertEquals(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED, qr.getStatus());
    assertEquals("http://example.org/Questionnaire/HomeOxygenTherapy", qr.getQuestionnaire());
    assertEquals("Patient/p1", qr.getSubject().getReference());
    // Display item is skipped; the four answerable items remain
    assertEquals(4, qr.getItem().size());
    assertTrue(qr.getItem().get(0).getAnswerFirstRep().hasValueStringType());
    assertTrue(((BooleanType) qr.getItem().get(1).getAnswerFirstRep().getValue()).booleanValue());
    Coding chosen = (Coding) qr.getItem().get(2).getAnswerFirstRep().getValue();
    assertEquals("LA33-6", chosen.getCode());
    assertEquals("4.1", qr.getItem().get(3).getItem().get(0).getLinkId());
    assertTrue(qr.getItem().get(3).getItem().get(0).getAnswerFirstRep().hasValueIntegerType());
  }
}
