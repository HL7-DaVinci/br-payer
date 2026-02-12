package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Questionnaire.EnableWhenBehavior;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemOperator;
import org.hl7.fhir.r4.model.QuestionnaireResponse;
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EnableWhenEvaluatorTest {

  private EnableWhenEvaluator evaluator;

  @BeforeEach
  void setUp() {
    evaluator = new EnableWhenEvaluator();
  }

  // ============================================================
  // EXISTS OPERATOR
  // ============================================================

  @Nested
  @DisplayName("Exists operator")
  class ExistsOperator {

    @Test
    @DisplayName("exists=true with answer present > enabled")
    void existsTrue_withAnswer() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(true));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new BooleanType(true)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("exists=true with no answer > disabled")
    void existsTrue_withoutAnswer() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(true));

      Map<String, List<Type>> answers = Map.of("q1", List.of());

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("exists=false with no answer > enabled")
    void existsFalse_withoutAnswer() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(false));

      Map<String, List<Type>> answers = Map.of("q1", List.of());

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("exists=false with answer present > disabled")
    void existsFalse_withAnswer() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(false));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new StringType("value")));

      assertFalse(evaluator.isEnabled(item, answers));
    }
  }

  // ============================================================
  // EQUALITY OPERATORS
  // ============================================================

  @Nested
  @DisplayName("Equality operators")
  class EqualityOperators {

    @Test
    @DisplayName("= with matching boolean > enabled")
    void equalBoolean_match() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EQUAL, new BooleanType(true));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new BooleanType(true)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("= with non-matching boolean > disabled")
    void equalBoolean_noMatch() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EQUAL, new BooleanType(true));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new BooleanType(false)));

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("= with matching integer > enabled")
    void equalInteger_match() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EQUAL, new IntegerType(42));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(42)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("!= with different values > enabled")
    void notEqual_different() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.NOT_EQUAL, new StringType("a"));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new StringType("b")));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("!= with same values > disabled")
    void notEqual_same() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.NOT_EQUAL, new StringType("a"));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new StringType("a")));

      assertFalse(evaluator.isEnabled(item, answers));
    }
  }

  // ============================================================
  // COMPARISON OPERATORS
  // ============================================================

  @Nested
  @DisplayName("Comparison operators")
  class ComparisonOperators {

    @Test
    @DisplayName("> with larger value > enabled")
    void greaterThan_larger() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.GREATER_THAN, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(10)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("> with equal value > disabled")
    void greaterThan_equal() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.GREATER_THAN, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(5)));

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("< with smaller value > enabled")
    void lessThan_smaller() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.LESS_THAN, new IntegerType(10));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(5)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName(">= with equal value > enabled")
    void greaterOrEqual_equal() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.GREATER_OR_EQUAL, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(5)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName(">= with non-orderable values > disabled")
    void greaterOrEqual_nonOrderable() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.GREATER_OR_EQUAL, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new StringType("abc")));

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("<= with larger value > disabled")
    void lessOrEqual_larger() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.LESS_OR_EQUAL, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new IntegerType(10)));

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("<= with non-orderable values > disabled")
    void lessOrEqual_nonOrderable() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.LESS_OR_EQUAL, new IntegerType(5));

      Map<String, List<Type>> answers = Map.of("q1", List.of(new StringType("abc")));

      assertFalse(evaluator.isEnabled(item, answers));
    }
  }

  // ============================================================
  // ENABLE BEHAVIOR
  // ============================================================

  @Nested
  @DisplayName("Enable behavior")
  class EnableBehaviorTests {

    @Test
    @DisplayName("ANY: one condition met > enabled")
    void anyBehavior_oneConditionMet() {
      QuestionnaireItemComponent item = new QuestionnaireItemComponent();
      item.setLinkId("target");
      item.setEnableBehavior(EnableWhenBehavior.ANY);
      item.addEnableWhen()
          .setQuestion("q1")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));
      item.addEnableWhen()
          .setQuestion("q2")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));

      Map<String, List<Type>> answers = Map.of(
          "q1", List.of(new BooleanType(true)),
          "q2", List.of(new BooleanType(false)));

      assertTrue(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("ALL: one condition unmet > disabled")
    void allBehavior_oneConditionUnmet() {
      QuestionnaireItemComponent item = new QuestionnaireItemComponent();
      item.setLinkId("target");
      item.setEnableBehavior(EnableWhenBehavior.ALL);
      item.addEnableWhen()
          .setQuestion("q1")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));
      item.addEnableWhen()
          .setQuestion("q2")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));

      Map<String, List<Type>> answers = Map.of(
          "q1", List.of(new BooleanType(true)),
          "q2", List.of(new BooleanType(false)));

      assertFalse(evaluator.isEnabled(item, answers));
    }

    @Test
    @DisplayName("ALL: all conditions met > enabled")
    void allBehavior_allConditionsMet() {
      QuestionnaireItemComponent item = new QuestionnaireItemComponent();
      item.setLinkId("target");
      item.setEnableBehavior(EnableWhenBehavior.ALL);
      item.addEnableWhen()
          .setQuestion("q1")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new BooleanType(true));
      item.addEnableWhen()
          .setQuestion("q2")
          .setOperator(QuestionnaireItemOperator.EQUAL)
          .setAnswer(new IntegerType(5));

      Map<String, List<Type>> answers = Map.of(
          "q1", List.of(new BooleanType(true)),
          "q2", List.of(new IntegerType(5)));

      assertTrue(evaluator.isEnabled(item, answers));
    }
  }

  // ============================================================
  // NO ENABLE WHEN
  // ============================================================

  @Test
  @DisplayName("No enableWhen > always enabled")
  void noEnableWhen_alwaysEnabled() {
    QuestionnaireItemComponent item = new QuestionnaireItemComponent();
    item.setLinkId("simple");

    assertTrue(evaluator.isEnabled(item, Map.of()));
  }

  // ============================================================
  // BUILD ANSWER INDEX
  // ============================================================

  @Nested
  @DisplayName("buildAnswerIndex")
  class BuildAnswerIndex {

    @Test
    @DisplayName("Builds index from flat items")
    void flatItems() {
      QuestionnaireResponse qr = new QuestionnaireResponse();
      qr.addItem().setLinkId("q1")
          .addAnswer().setValue(new StringType("hello"));
      qr.addItem().setLinkId("q2")
          .addAnswer().setValue(new IntegerType(42));

      Map<String, List<Type>> index = evaluator.buildAnswerIndex(qr);

      assertEquals(2, index.size());
      assertEquals("hello", ((StringType) index.get("q1").get(0)).getValue());
      assertEquals(42, ((IntegerType) index.get("q2").get(0)).getValue());
    }

    @Test
    @DisplayName("Builds index with nested items")
    void nestedItems() {
      QuestionnaireResponse qr = new QuestionnaireResponse();
      QuestionnaireResponseItemComponent group = qr.addItem();
      group.setLinkId("group1");
      group.addItem().setLinkId("q1.1")
          .addAnswer().setValue(new BooleanType(true));
      group.addItem().setLinkId("q1.2")
          .addAnswer().setValue(new StringType("nested"));

      Map<String, List<Type>> index = evaluator.buildAnswerIndex(qr);

      assertEquals(2, index.size()); // q1.1, q1.2
      assertFalse(index.containsKey("group1"));
      assertTrue(((BooleanType) index.get("q1.1").get(0)).booleanValue());
      assertEquals("nested", ((StringType) index.get("q1.2").get(0)).getValue());
    }

    @Test
    @DisplayName("Unanswered items are excluded from index")
    void unansweredItemsExcluded() {
      QuestionnaireResponse qr = new QuestionnaireResponse();
      qr.addItem().setLinkId("q1"); // displayed, unanswered
      qr.addItem().setLinkId("q2")
          .addAnswer().setValue(new IntegerType(7));

      Map<String, List<Type>> index = evaluator.buildAnswerIndex(qr);

      assertEquals(1, index.size());
      assertFalse(index.containsKey("q1"));
      assertEquals(7, ((IntegerType) index.get("q2").get(0)).getValue());
    }

    @Test
    @DisplayName("Empty QR produces empty index")
    void emptyQr() {
      Map<String, List<Type>> index = evaluator.buildAnswerIndex(new QuestionnaireResponse());
      assertTrue(index.isEmpty());
    }

    @Test
    @DisplayName("Null QR produces empty index")
    void nullQr() {
      Map<String, List<Type>> index = evaluator.buildAnswerIndex(null);
      assertTrue(index.isEmpty());
    }
  }

  // ============================================================
  // CAN EVALUATE
  // ============================================================

  @Nested
  @DisplayName("canEvaluate")
  class CanEvaluate {

    @Test
    @DisplayName("All referenced questions answered > can evaluate")
    void allQuestionsAnswered() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(true));

      assertTrue(evaluator.canEvaluate(item, Set.of("q1")));
    }

    @Test
    @DisplayName("Referenced question unanswered > cannot evaluate")
    void questionUnanswered() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(true));

      assertFalse(evaluator.canEvaluate(item, Set.of("q2")));
    }

    @Test
    @DisplayName("exists=false with question unanswered > can evaluate")
    void existsFalseUnanswered_canEvaluate() {
      QuestionnaireItemComponent item = itemWithEnableWhen(
          "q1", QuestionnaireItemOperator.EXISTS, new BooleanType(false));

      assertTrue(evaluator.canEvaluate(item, Set.of()));
    }

    @Test
    @DisplayName("No enableWhen > can always evaluate")
    void noEnableWhen() {
      QuestionnaireItemComponent item = new QuestionnaireItemComponent();
      item.setLinkId("simple");

      assertTrue(evaluator.canEvaluate(item, Set.of()));
    }
  }

  // ============================================================
  // HELPERS
  // ============================================================

  private QuestionnaireItemComponent itemWithEnableWhen(
      String question, QuestionnaireItemOperator operator, Type answer) {
    QuestionnaireItemComponent item = new QuestionnaireItemComponent();
    item.setLinkId("target");
    item.addEnableWhen()
        .setQuestion(question)
        .setOperator(operator)
        .setAnswer(answer);
    return item;
  }
}
