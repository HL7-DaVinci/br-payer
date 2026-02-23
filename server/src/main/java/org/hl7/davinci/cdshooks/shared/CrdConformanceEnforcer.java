package org.hl7.davinci.cdshooks.shared;

import java.util.Date;
import java.util.List;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.VisionPrescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseJson;
import ca.uhn.hapi.fhir.cdshooks.api.json.CdsServiceResponseSystemActionJson;

import static org.hl7.davinci.common.CrdConstants.COVERAGE_INFO_EXT;

/**
 * Static utility that enforces CRD profile conformance on CDS Hooks response resources.
 */
public final class CrdConformanceEnforcer {

  private static final Logger logger = LoggerFactory.getLogger(CrdConformanceEnforcer.class);

  private CrdConformanceEnforcer() {
  }

  /**
   * Ensures all system action resources conform to CRD profile requirements.
   * Sets default values for required fields where possible and logs warnings for missing fields.
   */
  public static void enforce(CdsServiceResponseJson response, String hookName) {
    if (response.getServiceActions() == null) {
      return;
    }

    Date now = new Date();
    boolean isSecondary = isSecondaryHook(hookName);

    for (CdsServiceResponseSystemActionJson action : response.getServiceActions()) {
      if (action == null || action.getResource() == null) {
        continue;
      }

      IBaseResource resource = action.getResource();
      if (resource instanceof Resource r) {
        ensureResourceConformance(r, now);
      }

      // Per CRD spec: Secondary hooks (encounter-start, encounter-discharge,
      // order-select) MAY return coverage-information but SHALL NOT request clinical
      // or administrative documentation.
      if (isSecondary && resource instanceof DomainResource dr) {
        stripDocNeededFromSecondaryHook(dr);
      }
    }
  }

  /**
   * Ensures a resource conforms to CRD profile requirements.
   * Sets defaults for status, intent, and date fields. Logs warnings for other required fields.
   */
  public static void ensureResourceConformance(Resource resource, Date defaultDate) {
    if (resource instanceof ServiceRequest sr) {
      if (!sr.hasStatus()) {
        sr.setStatus(ServiceRequest.ServiceRequestStatus.DRAFT);
      }
      if (!sr.hasIntent()) {
        sr.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
      }
      if (!sr.hasAuthoredOn()) {
        sr.setAuthoredOn(defaultDate);
      }
      logMissingRequiredFields(sr, "ServiceRequest", "code", "subject", "requester");

    } else if (resource instanceof DeviceRequest dr) {
      if (!dr.hasStatus()) {
        dr.setStatus(DeviceRequest.DeviceRequestStatus.DRAFT);
      }
      if (!dr.hasIntent()) {
        dr.setIntent(DeviceRequest.RequestIntent.ORDER);
      }
      if (!dr.hasAuthoredOn()) {
        dr.setAuthoredOn(defaultDate);
      }
      logMissingRequiredFields(dr, "DeviceRequest", "code", "subject", "requester");

    } else if (resource instanceof MedicationRequest mr) {
      if (!mr.hasStatus()) {
        mr.setStatus(MedicationRequest.MedicationRequestStatus.DRAFT);
      }
      if (!mr.hasIntent()) {
        mr.setIntent(MedicationRequest.MedicationRequestIntent.ORDER);
      }
      if (!mr.hasAuthoredOn()) {
        mr.setAuthoredOn(defaultDate);
      }
      logMissingRequiredFields(mr, "MedicationRequest", "medication", "subject", "requester");

    } else if (resource instanceof NutritionOrder no) {
      if (!no.hasStatus()) {
        no.setStatus(NutritionOrder.NutritionOrderStatus.DRAFT);
      }
      if (!no.hasIntent()) {
        no.setIntent(NutritionOrder.NutritiionOrderIntent.ORDER);
      }
      if (!no.hasDateTime()) {
        no.setDateTime(defaultDate);
      }
      logMissingRequiredFields(no, "NutritionOrder", "patient", "orderer");

    } else if (resource instanceof CommunicationRequest cr) {
      if (!cr.hasStatus()) {
        cr.setStatus(CommunicationRequest.CommunicationRequestStatus.DRAFT);
      }
      if (!cr.hasAuthoredOn()) {
        cr.setAuthoredOn(defaultDate);
      }
      logMissingRequiredFields(cr, "CommunicationRequest", "subject", "requester");

    } else if (resource instanceof VisionPrescription vp) {
      if (!vp.hasStatus()) {
        vp.setStatus(VisionPrescription.VisionStatus.DRAFT);
      }
      if (!vp.hasDateWritten()) {
        vp.setDateWritten(defaultDate);
      }
      logMissingRequiredFields(vp, "VisionPrescription", "patient", "prescriber", "lensSpecification");
    }
  }

  /**
   * Checks if a hook is a secondary hook that cannot request documentation.
   * Per CRD IG: encounter-start, encounter-discharge, and order-select are secondary hooks.
   */
  public static boolean isSecondaryHook(String hookName) {
    return "encounter-start".equals(hookName) ||
        "encounter-discharge".equals(hookName) ||
        "order-select".equals(hookName);
  }

  /**
   * Checks if a hook is a primary hook that requires mandatory coverage-information.
   * Per CRD IG: order-sign, order-dispatch, and appointment-book are primary hooks.
   */
  public static boolean isPrimaryHook(String hookName) {
    return "order-sign".equals(hookName) ||
        "order-dispatch".equals(hookName) ||
        "appointment-book".equals(hookName);
  }

  /**
   * Strips doc-needed extensions from coverage-information on secondary hook responses.
   * Per CRD: Secondary hooks SHALL NOT request clinical or administrative documentation
   * if coverage information is returned.
   */
  private static void stripDocNeededFromSecondaryHook(DomainResource resource) {
    Extension coverageInfoExt = resource.getExtensionByUrl(COVERAGE_INFO_EXT);
    if (coverageInfoExt == null) {
      return;
    }

    List<Extension> docNeededExts = coverageInfoExt.getExtensionsByUrl("doc-needed");
    if (!docNeededExts.isEmpty()) {
      logger.warn("Stripping doc-needed from secondary hook response - per CRD spec, "
          + "secondary hooks SHALL NOT request documentation");
      coverageInfoExt.getExtension().removeAll(docNeededExts);

      List<Extension> docPurposeExts = coverageInfoExt.getExtensionsByUrl("doc-purpose");
      if (!docPurposeExts.isEmpty()) {
        coverageInfoExt.getExtension().removeAll(docPurposeExts);
      }
      List<Extension> questionnaireExts = coverageInfoExt.getExtensionsByUrl("questionnaire");
      if (!questionnaireExts.isEmpty()) {
        coverageInfoExt.getExtension().removeAll(questionnaireExts);
      }
    }
  }

  /**
   * Logs warnings for required fields that are missing and cannot be auto-filled.
   */
  private static void logMissingRequiredFields(Resource resource, String resourceType, String... fields) {
    String resourceId = resource.hasIdElement() ? resource.getIdElement().toUnqualifiedVersionless().getValue()
        : "unknown";

    for (String field : fields) {
      boolean missing = switch (field) {
        case "code" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasCode();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasCode();
          yield false;
        }
        case "medication" -> resource instanceof MedicationRequest mr && !mr.hasMedication();
        case "subject" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasSubject();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasSubject();
          if (resource instanceof MedicationRequest mr)
            yield !mr.hasSubject();
          yield false;
        }
        case "patient" -> {
          if (resource instanceof NutritionOrder no)
            yield !no.hasPatient();
          if (resource instanceof CommunicationRequest cr)
            yield !cr.hasSubject();
          if (resource instanceof VisionPrescription vp)
            yield !vp.hasPatient();
          yield false;
        }
        case "requester" -> {
          if (resource instanceof ServiceRequest sr)
            yield !sr.hasRequester();
          if (resource instanceof DeviceRequest dr)
            yield !dr.hasRequester();
          if (resource instanceof MedicationRequest mr)
            yield !mr.hasRequester();
          if (resource instanceof CommunicationRequest cr)
            yield !cr.hasRequester();
          yield false;
        }
        case "orderer" -> resource instanceof NutritionOrder no && !no.hasOrderer();
        case "prescriber" -> resource instanceof VisionPrescription vp && !vp.hasPrescriber();
        case "lensSpecification" -> resource instanceof VisionPrescription vp && !vp.hasLensSpecification();
        default -> false;
      };

      if (missing) {
        logger.warn("CRD conformance: {} {} is missing required field '{}'", resourceType, resourceId, field);
      }
    }
  }
}
