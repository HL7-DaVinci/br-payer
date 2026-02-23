package org.hl7.davinci.cdshooks.shared;

import java.util.Date;
import java.util.List;

import org.hl7.davinci.common.CoverageInfoUtil;
import org.hl7.davinci.common.ResourceResolver;
import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RequestGroup;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.StringType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;

/**
 * Manages coverage-information extensions in CDS Hooks responses.
 * Handles extraction from CQL results, building system actions, and default coverage info.
 */
@Component
public class CoverageInfoHandler {

  private static final Logger logger = LoggerFactory.getLogger(CoverageInfoHandler.class);

  @Autowired
  private AppProperties appProperties;

  /**
   * Extracts the coverage-information extension from the RequestGroup action.
   * Adds server-required fields (coverage, date, coverage-assertion-id) if not present.
   *
   * @return the complete extension ready to add to the order, or null if not found
   */
  public Extension extractCoverageExtension(RequestGroup requestGroup, Coverage coverage) {
    Extension result = CoverageInfoUtil.extractCoverageExtension(
        requestGroup, coverage, appProperties.getServer_address());
    if (result != null) {
      logger.info("Coverage info extension found from CQL");
    }
    return result;
  }

  /**
   * Builds a system action to update the resource with the coverage-information extension.
   * Removes any existing coverage-information extensions from the resource before adding the new one.
   */
  public CdsServiceResponseSystemActionJson buildCoverageInfoSystemAction(Resource resource,
      Extension coverageInfoExt) {

    if (resource == null || coverageInfoExt == null) {
      return null;
    }

    Extension existingCoverage = findMatchingCoverageExtension(resource, coverageInfoExt);
    if (existingCoverage != null) {
      Extension normalizedExisting = normalizeCoverageExtension(existingCoverage);
      Extension normalizedNew = normalizeCoverageExtension(coverageInfoExt);
      if (normalizedExisting.equalsDeep(normalizedNew)) {
        return null;
      }
    }

    // Clone the resource and clean up stale CRD artifacts before adding new ones
    Resource updatedResource = resource.copy();

    // Extract coverage reference from the new extension to match against old ones
    String coverageRef = null;
    Extension coverageExtInNew = coverageInfoExt.getExtensionByUrl("coverage");
    if (coverageExtInNew != null && coverageExtInNew.getValue() instanceof Reference ref) {
      coverageRef = ref.getReference();
    }
    cleanupForResponse(updatedResource, coverageRef);

    // Add the new coverage-information extension
    if (updatedResource instanceof DeviceRequest dr) {
      dr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof MedicationRequest mr) {
      mr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof ServiceRequest sr) {
      sr.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof Appointment appt) {
      appt.addExtension(coverageInfoExt);
    } else if (updatedResource instanceof Encounter enc) {
      enc.addExtension(coverageInfoExt);
    }

    CdsServiceResponseSystemActionJson systemAction = new CdsServiceResponseSystemActionJson();
    systemAction.setType("update");
    systemAction.setDescription("Add coverage information to " + resource.fhirType());
    systemAction.setResource(updatedResource);

    return systemAction;
  }

  /**
   * Checks if the response already contains a coverage-information system action.
   */
  public boolean hasCoverageInfoSystemAction(CdsServiceResponseJson response) {
    if (response.getServiceActions() == null) {
      return false;
    }
    return response.getServiceActions().stream()
        .anyMatch(action -> {
          if (action == null || action.getResource() == null) {
            return false;
          }
          if (action.getResource() instanceof DomainResource resource) {
            return resource.hasExtension(COVERAGE_INFO_EXT);
          }
          return false;
        });
  }

  /**
   * Adds default coverage-information system actions when no rules matched.
   * Per CRD IG: Returns "conditional" when coverage exists but no rules matched.
   */
  public void addDefaultCoverageInfo(CdsServiceResponseJson response, ResolvedResources context,
      List<Resource> resources) {
    for (Resource resource : resources) {
      if (resource instanceof DomainResource domainResource
          && domainResource.hasExtension(COVERAGE_INFO_EXT)) {
        continue;
      }
      Extension coverageInfoExt = buildDefaultCoverageExtension(context);
      if (coverageInfoExt == null) {
        continue;
      }
      CdsServiceResponseSystemActionJson systemAction = buildCoverageInfoSystemAction(resource, coverageInfoExt);
      if (systemAction != null) {
        response.addServiceAction(systemAction);
      }
    }
  }

  /**
   * Builds a default coverage-information extension based on available context.
   */
  public Extension buildDefaultCoverageExtension(ResolvedResources context) {
    Extension coverageInfoExt = new Extension(COVERAGE_INFO_EXT);
    Coverage coverage = context.getCoverage();

    if (coverage == null || !coverage.hasIdElement()) {
      logger.warn("Cannot build default coverage extension: coverage or coverage ID is missing");
      return null;
    }

    // Coverage reference
    Extension coverageRefExt = new Extension("coverage");
    String idPart = coverage.getIdElement().getIdPart();
    Reference coverageRef = new Reference("Coverage/" + (idPart != null ? idPart : "unknown"));
    coverageRefExt.setValue(coverageRef);
    coverageInfoExt.addExtension(coverageRefExt);

    // Coverage resource exists but no rules matched = conditional
    Extension coveredExt = new Extension("covered");
    coveredExt.setValue(new CodeType("conditional"));
    coverageInfoExt.addExtension(coveredExt);

    // Date
    Extension dateExt = new Extension("date");
    dateExt.setValue(new DateType(new Date()));
    coverageInfoExt.addExtension(dateExt);

    // Coverage assertion ID
    Extension assertionIdExt = new Extension("coverage-assertion-id");
    String assertionId = "default-" + System.currentTimeMillis();
    assertionIdExt.setValue(new StringType(assertionId));
    coverageInfoExt.addExtension(assertionIdExt);

    return coverageInfoExt;
  }

  /**
   * Removes stale CRD artifacts from a resource for a specific coverage.
   * Only removes coverage-information extensions that reference the same coverage.
   */
  private void cleanupForResponse(Resource resource, String coverageRef) {
    if (resource instanceof DeviceRequest dr) {
      dr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof MedicationRequest mr) {
      mr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof ServiceRequest sr) {
      sr.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof Appointment appt) {
      appt.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    } else if (resource instanceof Encounter enc) {
      enc.getExtension().removeIf(ext -> isSameCoverageExtension(ext, coverageRef));
    }
  }

  private boolean isSameCoverageExtension(Extension ext, String coverageRef) {
    if (!COVERAGE_INFO_EXT.equals(ext.getUrl())) {
      return false;
    }
    Extension coverageExt = ext.getExtensionByUrl("coverage");
    if (coverageExt == null || !(coverageExt.getValue() instanceof Reference ref)) {
      return false;
    }
    String extCoverageRef = ref.getReference();
    return ResourceResolver.referencesMatch(coverageRef, extCoverageRef);
  }

  private Extension findMatchingCoverageExtension(Resource resource, Extension coverageInfoExt) {
    if (!(resource instanceof DomainResource domainResource)) {
      return null;
    }

    String coverageRef = null;
    Extension coverageExt = coverageInfoExt.getExtensionByUrl("coverage");
    if (coverageExt != null && coverageExt.getValue() instanceof Reference ref) {
      coverageRef = ref.getReference();
    }

    for (Extension ext : domainResource.getExtension()) {
      if (!COVERAGE_INFO_EXT.equals(ext.getUrl())) {
        continue;
      }
      if (coverageRef == null || isSameCoverageExtension(ext, coverageRef)) {
        return ext;
      }
    }

    return null;
  }

  private Extension normalizeCoverageExtension(Extension extension) {
    Extension normalized = extension.copy();
    normalized.getExtension().removeIf(ext -> "coverage-assertion-id".equals(ext.getUrl())
        || "date".equals(ext.getUrl()));
    return normalized;
  }
}
