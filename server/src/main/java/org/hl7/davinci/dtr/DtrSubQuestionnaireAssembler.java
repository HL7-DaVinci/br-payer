package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;

/**
 * Resolves and inlines sub-questionnaire references within a Questionnaire.
 * Handles the SDC sub-questionnaire extension by replacing display items
 * with the referenced Questionnaire's items.
 *
 * @see <a href="http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire">
 *   SDC Sub-Questionnaire Extension</a>
 */
@Component
public class DtrSubQuestionnaireAssembler {

  private static final Logger logger = LoggerFactory.getLogger(DtrSubQuestionnaireAssembler.class);

  private static final String SUB_QUESTIONNAIRE_EXT_URL =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-subQuestionnaire";

  private final DaoRegistry daoRegistry;

  public DtrSubQuestionnaireAssembler(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  /**
   * Resolve and inline sub-questionnaire references. Mutates the Questionnaire in place.
   *
   * @return list of warnings for unresolvable or circular sub-questionnaires
   */
  public List<String> assemble(Questionnaire questionnaire) {
    List<String> warnings = new ArrayList<>();
    Set<String> visited = new HashSet<>();

    // Track the root questionnaire's canonical to detect self-references
    String rootCanonical = DtrFhirUtil.toVersionSpecific(
        questionnaire.getUrl(), questionnaire.getVersion());
    if (rootCanonical != null) {
      visited.add(rootCanonical);
    }

    assembleItems(questionnaire.getItem(), warnings, visited);
    return warnings;
  }

  private void assembleItems(List<QuestionnaireItemComponent> items,
      List<String> warnings, Set<String> visited) {

    for (int i = 0; i < items.size(); i++) {
      QuestionnaireItemComponent item = items.get(i);

      Extension subQExt = item.getExtensionByUrl(SUB_QUESTIONNAIRE_EXT_URL);
      if (subQExt != null && subQExt.hasValue()) {
        String canonical = subQExt.getValue().primitiveValue();

        if (visited.contains(canonical)) {
          String warning = "Circular sub-questionnaire reference detected: " + canonical;
          logger.warn(warning);
          warnings.add(warning);
          continue;
        }

        Questionnaire subQ = DtrFhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
        if (subQ == null) {
          String warning = "Sub-questionnaire not found: " + canonical;
          logger.warn(warning);
          warnings.add(warning);
          continue;
        }

        // Inline the sub-questionnaire items
        visited.add(canonical);

        List<QuestionnaireItemComponent> inlinedItems = new ArrayList<>();
        for (QuestionnaireItemComponent subItem : subQ.getItem()) {
          QuestionnaireItemComponent copy = subItem.copy();
          prefixLinkIds(copy, item.getLinkId() + ".");
          inlinedItems.add(copy);
        }

        // Replace the item's children with inlined items and remove the extension
        item.setItem(inlinedItems);
        item.removeExtension(SUB_QUESTIONNAIRE_EXT_URL);

        // Recursively process inlined items
        assembleItems(inlinedItems, warnings, visited);

        visited.remove(canonical);
      }

      // Recursively process existing child items
      if (item.hasItem() && subQExt == null) {
        assembleItems(item.getItem(), warnings, visited);
      }
    }
  }

  private void prefixLinkIds(QuestionnaireItemComponent item, String prefix) {
    item.setLinkId(prefix + item.getLinkId());
    if (item.hasItem()) {
      for (QuestionnaireItemComponent child : item.getItem()) {
        prefixLinkIds(child, prefix);
      }
    }
  }
}
