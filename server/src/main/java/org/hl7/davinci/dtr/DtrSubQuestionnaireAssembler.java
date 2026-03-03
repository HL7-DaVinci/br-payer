package org.hl7.davinci.dtr;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hl7.davinci.common.FhirUtil;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Questionnaire;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent;
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static org.hl7.davinci.common.FhirConstants.*;
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
    String rootCanonical = FhirUtil.toVersionSpecific(
        questionnaire.getUrl(), questionnaire.getVersion());
    if (rootCanonical != null) {
      visited.add(rootCanonical);
    }

    assembleItems(questionnaire, questionnaire.getItem(), warnings, visited);
    return warnings;
  }

  private void assembleItems(Questionnaire parent, List<QuestionnaireItemComponent> items,
      List<String> warnings, Set<String> visited) {

    for (int i = 0; i < items.size(); i++) {
      QuestionnaireItemComponent item = items.get(i);

      Extension subQExt = item.getExtensionByUrl(SUB_QUESTIONNAIRE_EXT);
      if (subQExt != null && subQExt.hasValue()) {
        String canonical = subQExt.getValue().primitiveValue();

        if (visited.contains(canonical)) {
          String warning = "Circular sub-questionnaire reference detected: " + canonical;
          logger.warn(warning);
          warnings.add(warning);
          continue;
        }

        Questionnaire subQ = FhirUtil.resolveByCanonical(daoRegistry, Questionnaire.class, canonical);
        if (subQ == null) {
          String warning = "Sub-questionnaire not found: " + canonical;
          logger.warn(warning);
          warnings.add(warning);
          continue;
        }

        // Inline the sub-questionnaire items
        visited.add(canonical);

        // Merge cqf-library extensions from sub-questionnaire into parent (deduplicated)
        mergeCqfLibraryExtensions(parent, subQ);

        List<QuestionnaireItemComponent> inlinedItems = new ArrayList<>();
        for (QuestionnaireItemComponent subItem : subQ.getItem()) {
          QuestionnaireItemComponent copy = subItem.copy();
          prefixLinkIds(copy, item.getLinkId() + ".");
          inlinedItems.add(copy);
        }

        // Replace the item's children with inlined items and remove the extension
        item.setItem(inlinedItems);
        item.removeExtension(SUB_QUESTIONNAIRE_EXT);
        item.setType(QuestionnaireItemType.GROUP);

        // Recursively process inlined items
        assembleItems(parent, inlinedItems, warnings, visited);

        visited.remove(canonical);
      }

      // Recursively process existing child items
      if (item.hasItem() && subQExt == null) {
        assembleItems(parent, item.getItem(), warnings, visited);
      }
    }
  }

  /**
   * Merge cqf-library extensions from a sub-questionnaire into the parent,
   * skipping any canonical URLs already present on the parent.
   */
  private void mergeCqfLibraryExtensions(Questionnaire parent, Questionnaire subQ) {
    Set<String> existingCanonicals = new HashSet<>();
    for (Extension ext : parent.getExtensionsByUrl(CQF_LIBRARY_EXT)) {
      if (ext.hasValue()) {
        existingCanonicals.add(ext.getValue().primitiveValue());
      }
    }

    for (Extension ext : subQ.getExtensionsByUrl(CQF_LIBRARY_EXT)) {
      if (ext.hasValue()) {
        String canonical = ext.getValue().primitiveValue();
        if (canonical != null && !existingCanonicals.contains(canonical)) {
          parent.addExtension(ext.copy());
          existingCanonicals.add(canonical);
        }
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
