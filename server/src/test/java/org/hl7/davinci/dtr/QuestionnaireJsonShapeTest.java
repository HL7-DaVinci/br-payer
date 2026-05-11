package org.hl7.davinci.dtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Asserts JSON-shape requirements for Questionnaires under library/:
 *  - Q1 + Q2 declare a `clinical` launchContext bound to MedicationRequest
 *  - No Questionnaire declares a QuestionnaireResponse-typed launchContext
 *    (DTR pre-pop EHR rule: each Q reads from EHR, not from a prior QR)
 */
class QuestionnaireJsonShapeTest {

  private static final String SDC_LAUNCH_CONTEXT_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-launchContext";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path LIBRARY_ROOT = Paths.get("../library");

  @Test
  @DisplayName("Q1 declares a clinical launchContext bound to MedicationRequest")
  void q1_declares_medicationRequest_launchContext() throws IOException {
    JsonNode q = readQuestionnaireJson("ImmunosuppressiveDrugs/Questionnaire-ImmunosuppressiveDrugs.json");
    Optional<JsonNode> lc = streamLaunchContexts(q)
        .filter(ext -> "clinical".equals(
            findSubExt(ext, "name").path("valueCoding").path("code").asText()))
        .findFirst();
    assertTrue(lc.isPresent(), "Q1 must declare a clinical launchContext");
    assertEquals("MedicationRequest", findSubExt(lc.get(), "type").path("valueCode").asText());
  }

  @Test
  @DisplayName("Q2 declares a clinical launchContext bound to MedicationRequest")
  void q2_declares_medicationRequest_launchContext() throws IOException {
    JsonNode q = readQuestionnaireJson(
        "ImmunosuppressiveDrugs/Questionnaire-ImmunosuppressiveDrugsProgressNote.json");
    Optional<JsonNode> lc = streamLaunchContexts(q)
        .filter(ext -> "clinical".equals(
            findSubExt(ext, "name").path("valueCoding").path("code").asText()))
        .findFirst();
    assertTrue(lc.isPresent(), "Q2 must declare a clinical launchContext");
    assertEquals("MedicationRequest", findSubExt(lc.get(), "type").path("valueCode").asText());
  }

  @ParameterizedTest
  @MethodSource("allQuestionnaires")
  @DisplayName("No Questionnaire declares a QuestionnaireResponse-typed launchContext")
  void no_questionnaire_declares_a_qr_typed_launchContext(Path qPath) throws IOException {
    JsonNode q = MAPPER.readTree(qPath.toFile());
    streamLaunchContexts(q).forEach(ext -> {
      String type = findSubExt(ext, "type").path("valueCode").asText();
      assertNotEquals("QuestionnaireResponse", type,
          "DTR pre-pop EHR rule: Questionnaires SHALL pull from EHR; "
              + "QR-typed launchContexts are not part of this design (file: " + qPath + ")");
    });
  }

  static Stream<Path> allQuestionnaires() throws IOException {
    return Files.walk(LIBRARY_ROOT)
        .filter(Files::isRegularFile)
        .filter(p -> p.getFileName().toString().startsWith("Questionnaire-"))
        .filter(p -> p.getFileName().toString().endsWith(".json"));
  }

  private static JsonNode readQuestionnaireJson(String relativePath) throws IOException {
    return MAPPER.readTree(LIBRARY_ROOT.resolve(relativePath).toFile());
  }

  private static Stream<JsonNode> streamLaunchContexts(JsonNode q) {
    JsonNode extensions = q.path("extension");
    if (!extensions.isArray()) return Stream.empty();
    List<JsonNode> matches = new ArrayList<>();
    for (JsonNode ext : extensions) {
      if (SDC_LAUNCH_CONTEXT_URL.equals(ext.path("url").asText())) {
        matches.add(ext);
      }
    }
    return matches.stream();
  }

  private static JsonNode findSubExt(JsonNode parent, String url) {
    JsonNode subExts = parent.path("extension");
    if (!subExts.isArray()) return MAPPER.createObjectNode();
    Iterator<JsonNode> it = subExts.elements();
    while (it.hasNext()) {
      JsonNode sub = it.next();
      if (url.equals(sub.path("url").asText())) {
        return sub;
      }
    }
    return MAPPER.createObjectNode();
  }
}
