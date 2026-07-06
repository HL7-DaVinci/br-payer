package org.hl7.davinci.scenarios.cdex;

import java.util.Date;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;

/**
 * Produces a plausibly-completed QuestionnaireResponse for a Questionnaire so
 * the CDex testbed can demonstrate $submit-attachment without hand-authoring
 * answers. Answers are simple type-appropriate examples, not clinically
 * meaningful values.
 */
public final class QuestionnaireResponseGenerator {

  private QuestionnaireResponseGenerator() {
  }

  public static QuestionnaireResponse generate(Questionnaire questionnaire, String patientReference) {
    QuestionnaireResponse qr = new QuestionnaireResponse();
    qr.setStatus(QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED);
    qr.setQuestionnaire(questionnaire.getUrl());
    qr.setAuthored(new Date());
    if (patientReference != null) {
      qr.setSubject(new Reference(patientReference));
    }
    for (Questionnaire.QuestionnaireItemComponent item : questionnaire.getItem()) {
      QuestionnaireResponse.QuestionnaireResponseItemComponent answered = answerItem(item);
      if (answered != null) {
        qr.addItem(answered);
      }
    }
    return qr;
  }

  private static QuestionnaireResponse.QuestionnaireResponseItemComponent answerItem(
      Questionnaire.QuestionnaireItemComponent item) {

    if (item.getType() == Questionnaire.QuestionnaireItemType.DISPLAY) {
      return null;
    }
    QuestionnaireResponse.QuestionnaireResponseItemComponent response =
        new QuestionnaireResponse.QuestionnaireResponseItemComponent();
    response.setLinkId(item.getLinkId());
    if (item.hasText()) {
      response.setText(item.getText());
    }
    if (item.getType() == Questionnaire.QuestionnaireItemType.GROUP) {
      for (Questionnaire.QuestionnaireItemComponent child : item.getItem()) {
        QuestionnaireResponse.QuestionnaireResponseItemComponent answered = answerItem(child);
        if (answered != null) {
          response.addItem(answered);
        }
      }
      return response;
    }
    response.addAnswer().setValue(exampleAnswer(item));
    return response;
  }

  private static Type exampleAnswer(Questionnaire.QuestionnaireItemComponent item) {
    if (item.hasAnswerOption()) {
      return (Type) item.getAnswerOptionFirstRep().getValue().copy();
    }
    return switch (item.getType()) {
      case BOOLEAN -> new BooleanType(true);
      case DECIMAL -> new DecimalType("1.0");
      case INTEGER -> new IntegerType(1);
      case DATE -> new DateType(new Date());
      case DATETIME -> new DateTimeType(new Date());
      case QUANTITY -> new Quantity().setValue(1);
      default -> new StringType("Example response");
    };
  }
}
