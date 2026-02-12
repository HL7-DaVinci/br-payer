package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DecimalType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemEnableWhenComponent;
import org.hl7.fhir.r4.model.Questionnaire.EnableWhenBehavior;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemOperator;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

/**
 * Evaluates FHIR Questionnaire enableWhen conditions against QuestionnaireResponse answers.
 */
@Component
public class EnableWhenEvaluator {

  /**
   * Check if all enableWhen-referenced questions have answers in the index.
   * Returns false if a required referenced question has no answer record.
   * For EXISTS with answerBoolean=false, unanswered is evaluable.
   */
  public boolean canEvaluate(QuestionnaireItemComponent item, Set<String> answeredLinkIds) {
    if (!item.hasEnableWhen()) {
      return true;
    }
    for (QuestionnaireItemEnableWhenComponent ew : item.getEnableWhen()) {
      boolean questionAnswered = answeredLinkIds.contains(ew.getQuestion());
      if (!questionAnswered && !isExistsFalseCondition(ew)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Evaluate whether an item is enabled based on its enableWhen conditions.
   * Items without enableWhen are always enabled.
   */
  public boolean isEnabled(QuestionnaireItemComponent item, Map<String, List<Type>> answerIndex) {
    if (!item.hasEnableWhen()) {
      return true;
    }

    List<QuestionnaireItemEnableWhenComponent> conditions = item.getEnableWhen();
    boolean useAll = item.hasEnableBehavior()
        && item.getEnableBehavior() == EnableWhenBehavior.ALL;

    for (QuestionnaireItemEnableWhenComponent ew : conditions) {
      boolean conditionMet = evaluateCondition(ew, answerIndex);

      if (useAll && !conditionMet) {
        return false;
      }
      if (!useAll && conditionMet) {
        return true; // ANY behavior (default)
      }
    }

    // ALL: all passed; ANY: none passed
    return useAll;
  }

  /**
   * Build an index mapping linkId to answer values from a QuestionnaireResponse.
   * Only answered items are indexed; unanswered displayed items are intentionally excluded.
   */
  public Map<String, List<Type>> buildAnswerIndex(QuestionnaireResponse qr) {
    Map<String, List<Type>> index = new HashMap<>();
    if (qr != null && qr.hasItem()) {
      indexItems(qr.getItem(), index);
    }
    return index;
  }

  private void indexItems(List<QuestionnaireResponseItemComponent> items,
      Map<String, List<Type>> index) {
    for (QuestionnaireResponseItemComponent item : items) {
      List<Type> values = new ArrayList<>();
      for (QuestionnaireResponseItemAnswerComponent answer : item.getAnswer()) {
        if (answer.hasValue()) {
          values.add(answer.getValue());
        }
        // Recurse into nested answer items
        if (answer.hasItem()) {
          indexItems(answer.getItem(), index);
        }
      }
      if (!values.isEmpty()) {
        index.computeIfAbsent(item.getLinkId(), ignored -> new ArrayList<>()).addAll(values);
      }

      // Recurse into child items
      if (item.hasItem()) {
        indexItems(item.getItem(), index);
      }
    }
  }

  private boolean evaluateCondition(QuestionnaireItemEnableWhenComponent ew,
      Map<String, List<Type>> answerIndex) {
    String questionLinkId = ew.getQuestion();
    List<Type> answers = answerIndex.getOrDefault(questionLinkId, List.of());
    QuestionnaireItemOperator operator = ew.getOperator();
    Type expected = ew.getAnswer();

    if (operator == QuestionnaireItemOperator.EXISTS) {
      boolean expectExists = expected instanceof BooleanType
          && ((BooleanType) expected).booleanValue();
      boolean hasAnswer = !answers.isEmpty();
      return expectExists == hasAnswer;
    }

    // For non-exists operators, check if any answer satisfies the condition
    for (Type answer : answers) {
      if (compareValues(operator, answer, expected)) {
        return true;
      }
    }
    return false;
  }

  private boolean compareValues(QuestionnaireItemOperator operator, Type actual, Type expected) {
    if (actual == null || expected == null) {
      return false;
    }

    switch (operator) {
      case EQUAL:
        return valuesEqual(actual, expected);
      case NOT_EQUAL:
        return !valuesEqual(actual, expected);
      case GREATER_THAN:
        return orderedComparisonMatches(actual, expected, c -> c > 0);
      case LESS_THAN:
        return orderedComparisonMatches(actual, expected, c -> c < 0);
      case GREATER_OR_EQUAL:
        return orderedComparisonMatches(actual, expected, c -> c >= 0);
      case LESS_OR_EQUAL:
        return orderedComparisonMatches(actual, expected, c -> c <= 0);
      default:
        return false;
    }
  }

  private boolean valuesEqual(Type actual, Type expected) {
    // Handle boolean comparison
    if (actual instanceof BooleanType a && expected instanceof BooleanType b) {
      return a.booleanValue() == b.booleanValue();
    }
    // Handle integer comparison
    if (actual instanceof IntegerType a && expected instanceof IntegerType b) {
      return a.getValue().equals(b.getValue());
    }
    // Handle decimal comparison
    if (actual instanceof DecimalType a && expected instanceof DecimalType b) {
      return a.getValue().compareTo(b.getValue()) == 0;
    }
    // Handle string comparison
    if (actual instanceof StringType a && expected instanceof StringType b) {
      return a.getValue().equals(b.getValue());
    }
    // Handle date comparison
    if (actual instanceof DateType a && expected instanceof DateType b) {
      return a.getValue().equals(b.getValue());
    }
    // Handle Coding comparison
    if (actual instanceof Coding a && expected instanceof Coding b) {
      return codingsEqual(a, b);
    }
    // Fallback to HAPI deep equality
    return actual.equalsDeep(expected);
  }

  private boolean codingsEqual(Coding a, Coding b) {
    boolean systemMatch = (a.getSystem() == null && b.getSystem() == null)
        || (a.getSystem() != null && a.getSystem().equals(b.getSystem()));
    boolean codeMatch = (a.getCode() == null && b.getCode() == null)
        || (a.getCode() != null && a.getCode().equals(b.getCode()));
    return systemMatch && codeMatch;
  }

  private Integer compareOrdered(Type actual, Type expected) {
    if (actual instanceof IntegerType a && expected instanceof IntegerType b) {
      return Integer.compare(a.getValue(), b.getValue());
    }
    if (actual instanceof DecimalType a && expected instanceof DecimalType b) {
      return a.getValue().compareTo(b.getValue());
    }
    if (actual instanceof DateType a && expected instanceof DateType b) {
      return a.getValue().compareTo(b.getValue());
    }
    // Non-comparable types are not orderable.
    return null;
  }

  private boolean isExistsFalseCondition(QuestionnaireItemEnableWhenComponent ew) {
    if (ew.getOperator() != QuestionnaireItemOperator.EXISTS) {
      return false;
    }
    Type expected = ew.getAnswer();
    return expected instanceof BooleanType && !((BooleanType) expected).booleanValue();
  }

  private boolean orderedComparisonMatches(Type actual, Type expected,
      java.util.function.IntPredicate predicate) {
    Integer comparison = compareOrdered(actual, expected);
    return comparison != null && predicate.test(comparison);
  }
}
