package org.hl7.davinci.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.PlanDefinition;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.UsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hl7.davinci.dtr.DtrConstants.*;

import ca.uhn.fhir.context.FhirContext;

/**
 * Scans library directories for PlanDefinition and Questionnaire resources,
 * extracting metadata for test request generation. Supports both file-based
 * scanning (build time) and pre-parsed resource lists (runtime).
 */
public class LibraryScenarioScanner {

  private static final Logger logger = LoggerFactory.getLogger(LibraryScenarioScanner.class);

  private static final String USAGE_CONTEXT_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/usage-context-type";

  private static final Set<String> SUB_QUESTIONNAIRE_NAMES = Set.of("PatientInfo");

  static final Map<String, String> SYSTEM_TO_ORDER_TYPE = Map.of(
      "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "DeviceRequest",
      "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets", "DeviceRequest",
      "http://www.nlm.nih.gov/research/umls/rxnorm", "MedicationRequest",
      "http://www.ama-assn.org/go/cpt", "Appointment",
      "http://snomed.info/sct", "Appointment");

  private LibraryScenarioScanner() {}

  /** Scan a directory tree for PlanDefinition and Questionnaire JSON, return metadata. */
  public static List<ScenarioMetadata> scan(FhirContext ctx, Path libraryDir) throws IOException {
    List<Questionnaire> questionnaires = new ArrayList<>();
    List<PlanDefinition> planDefinitions = new ArrayList<>();

    try (Stream<Path> paths = Files.walk(libraryDir)) {
      paths.filter(p -> p.getFileName().toString().endsWith(".json"))
          .forEach(p -> {
            String filename = p.getFileName().toString();
            try {
              String json = Files.readString(p);
              if (filename.startsWith("Questionnaire-")) {
                questionnaires.add((Questionnaire) ctx.newJsonParser().parseResource(json));
              } else if (filename.startsWith("PlanDefinition-")) {
                planDefinitions.add((PlanDefinition) ctx.newJsonParser().parseResource(json));
              }
            } catch (IOException e) {
              logger.warn("Failed to read {}", p, e);
            }
          });
    }

    logger.info("Scanned library: {} Questionnaires, {} PlanDefinitions",
        questionnaires.size(), planDefinitions.size());

    return scan(questionnaires, planDefinitions);
  }

  /** Build scenario metadata from pre-parsed resource lists. */
  public static List<ScenarioMetadata> scan(List<Questionnaire> questionnaires,
      List<PlanDefinition> planDefinitions) {

    List<ScenarioMetadata> scenarios = new ArrayList<>();

    // Group Questionnaires by their matching PlanDefinition
    Map<PlanDefinition, List<Questionnaire>> pdToQuestionnaires = new LinkedHashMap<>();
    List<Questionnaire> unmatchedQuestionnaires = new ArrayList<>();

    for (Questionnaire q : questionnaires) {
      String qName = q.getName();
      if (qName == null || !q.hasUrl() || !q.getUrl().startsWith(DTR_QUESTIONNAIRE_PREFIX)) {
        continue;
      }
      if (SUB_QUESTIONNAIRE_NAMES.contains(qName)) {
        continue;
      }

      PlanDefinition matchedPd = findMatchingPlanDefinition(qName, planDefinitions);
      if (matchedPd != null) {
        pdToQuestionnaires.computeIfAbsent(matchedPd, k -> new ArrayList<>()).add(q);
      } else {
        unmatchedQuestionnaires.add(q);
      }
    }

    // One scenario per PlanDefinition, with all associated Questionnaire URLs
    for (Map.Entry<PlanDefinition, List<Questionnaire>> entry : pdToQuestionnaires.entrySet()) {
      PlanDefinition pd = entry.getKey();
      List<Questionnaire> associatedQs = entry.getValue();

      List<Coding> focusCodes = extractFocusCodes(pd);
      List<String> hookTriggers = extractHookTriggers(pd);
      String orderType = extractExplicitOrderType(pd);
      if (orderType == null) {
        orderType = inferOrderType(focusCodes);
      }
      List<String> qUrls = associatedQs.stream().map(Questionnaire::getUrl).toList();
      boolean isAdaptive = associatedQs.stream()
          .anyMatch(LibraryScenarioScanner::isAdaptiveQuestionnaire);
      boolean isAdaptiveSearch = isAdaptive && associatedQs.stream()
          .filter(LibraryScenarioScanner::isAdaptiveQuestionnaire)
          .anyMatch(q -> q.getMeta().hasProfile(Q_ADAPT_SEARCH_PROFILE));
      boolean hasInitialItems = isAdaptive && associatedQs.stream()
          .filter(LibraryScenarioScanner::isAdaptiveQuestionnaire)
          .anyMatch(LibraryScenarioScanner::hasNonConditionalFirstItem);

      scenarios.add(new ScenarioMetadata(
          toKebabCase(pd.getName()),
          pd.hasTitle() ? pd.getTitle() : pd.getName(),
          pd.hasDescription() ? pd.getDescription() : null,
          focusCodes,
          hookTriggers,
          orderType,
          qUrls,
          isAdaptive,
          isAdaptiveSearch,
          hasInitialItems));
    }

    // Orphan Questionnaires with no matching PlanDefinition
    for (Questionnaire q : unmatchedQuestionnaires) {
      boolean orphanAdaptive = isAdaptiveQuestionnaire(q);
      boolean orphanSearch = orphanAdaptive && q.getMeta().hasProfile(Q_ADAPT_SEARCH_PROFILE);
      scenarios.add(new ScenarioMetadata(
          toKebabCase(q.getName()),
          q.hasTitle() ? q.getTitle() : q.getName(),
          q.hasDescription() ? q.getDescription() : null,
          List.of(),
          List.of(),
          null,
          List.of(q.getUrl()),
          orphanAdaptive,
          orphanSearch,
          orphanAdaptive && hasNonConditionalFirstItem(q)));
    }

    scenarios.sort((a, b) -> a.name().compareTo(b.name()));
    return scenarios;
  }

  // ===== Matching =====

  /**
   * Finds the PlanDefinition whose name is the longest prefix of the Questionnaire name.
   * Convention: PlanDefinition "OpioidPrescribing" matches Questionnaire
   * "OpioidPrescribingJustification".
   */
  static PlanDefinition findMatchingPlanDefinition(String questionnaireName,
      List<PlanDefinition> planDefinitions) {
    PlanDefinition bestMatch = null;
    int longestPrefix = 0;

    for (PlanDefinition pd : planDefinitions) {
      String pdName = pd.getName();
      if (pdName != null && questionnaireName.startsWith(pdName)
          && pdName.length() > longestPrefix) {
        bestMatch = pd;
        longestPrefix = pdName.length();
      }
    }
    return bestMatch;
  }

  static List<Coding> extractFocusCodes(PlanDefinition pd) {
    List<Coding> codes = new ArrayList<>();
    for (UsageContext ctx : pd.getUseContext()) {
      if (ctx.hasCode()
          && USAGE_CONTEXT_TYPE_SYSTEM.equals(ctx.getCode().getSystem())
          && "focus".equals(ctx.getCode().getCode())
          && ctx.hasValueCodeableConcept()) {
        codes.addAll(ctx.getValueCodeableConcept().getCoding());
      }
    }
    return codes;
  }

  static List<String> extractHookTriggers(PlanDefinition pd) {
    Set<String> triggers = new LinkedHashSet<>();
    for (PlanDefinition.PlanDefinitionActionComponent action : pd.getAction()) {
      for (var trigger : action.getTrigger()) {
        if (trigger.hasName()) {
          triggers.add(trigger.getName());
        }
      }
    }
    return new ArrayList<>(triggers);
  }

  /**
   * Extracts an explicit order type from a PlanDefinition's useContext with code "task".
   * The value CodeableConcept text field specifies the FHIR resource type (e.g. "ServiceRequest").
   * Returns null if no explicit order type is declared.
   */
  static String extractExplicitOrderType(PlanDefinition pd) {
    for (UsageContext ctx : pd.getUseContext()) {
      if (ctx.hasCode()
          && USAGE_CONTEXT_TYPE_SYSTEM.equals(ctx.getCode().getSystem())
          && "task".equals(ctx.getCode().getCode())
          && ctx.hasValueCodeableConcept()
          && ctx.getValueCodeableConcept().hasText()) {
        return ctx.getValueCodeableConcept().getText();
      }
    }
    return null;
  }

  static String inferOrderType(List<Coding> focusCodes) {
    for (Coding code : focusCodes) {
      if (code.hasSystem()) {
        String type = SYSTEM_TO_ORDER_TYPE.get(code.getSystem());
        if (type != null) {
          return type;
        }
      }
    }
    return null;
  }

  /**
   * Returns true if the questionnaire's first top-level item has no enableWhen.
   * This is independent of declared profile.
   */
  static boolean hasNonConditionalFirstItem(Questionnaire q) {
    if (!q.hasItem()) {
      return false;
    }
    return !q.getItemFirstRep().hasEnableWhen();
  }

  static boolean isAdaptiveQuestionnaire(Questionnaire q) {
    if (q.hasExtension(QUESTIONNAIRE_ADAPTIVE_EXT)) {
      return true;
    }
    return q.getMeta().hasProfile(Q_ADAPT_PROFILE)
        || q.getMeta().hasProfile(Q_ADAPT_SEARCH_PROFILE);
  }

  /**
   * Converts PascalCase/camelCase to kebab-case, keeping consecutive uppercase
   * letters together (e.g. "OpioidPDMP" -> "opioid-pdmp").
   */
  static String toKebabCase(String camelCase) {
    if (camelCase == null || camelCase.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < camelCase.length(); i++) {
      char c = camelCase.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          char prev = camelCase.charAt(i - 1);
          if (!Character.isUpperCase(prev)) {
            sb.append('-');
          } else if (i + 1 < camelCase.length()
              && Character.isLowerCase(camelCase.charAt(i + 1))) {
            sb.append('-');
          }
        }
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /** Metadata extracted from a PlanDefinition (or orphan Questionnaire) for test request generation. */
  public record ScenarioMetadata(
      String id,
      String name,
      String description,
      List<Coding> focusCodes,
      List<String> hookTriggers,
      String orderType,
      List<String> questionnaireUrls,
      boolean isAdaptive,
      boolean isAdaptiveSearch,
      boolean hasInitialItems) {
  }
}
