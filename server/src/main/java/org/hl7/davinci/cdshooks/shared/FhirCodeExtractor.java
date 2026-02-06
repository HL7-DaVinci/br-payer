package org.hl7.davinci.cdshooks.shared;

import java.util.ArrayList;
import java.util.List;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.VisionPrescription;

import ca.uhn.fhir.rest.api.server.cdshooks.CdsServiceRequestJson;

/**
 * Static utility for extracting FHIR codes from CDS Hooks context resources.
 */
public final class FhirCodeExtractor {

  private FhirCodeExtractor() {
  }

  /**
   * DTR-friendly overload: extracts codes without a CDS Hooks request context.
   */
  public static List<Coding> extractCodes(Resource resource, boolean normalizeSystem) {
    return extractCodes(resource, normalizeSystem, null);
  }

  /**
   * Extracts codes from context resources (orders, appointments, encounters, etc.).
   * Optionally normalizes code systems from https:// to http:// for consistent matching.
   */
  public static List<Coding> extractCodes(Resource resource, boolean normalizeSystem, CdsServiceRequestJson request) {
    List<Coding> codes = new ArrayList<>();

    if (resource instanceof DeviceRequest deviceRequest) {
      if (deviceRequest.hasCodeCodeableConcept()) {
        codes.addAll(deviceRequest.getCodeCodeableConcept().getCoding());
      }
    } else if (resource instanceof CommunicationRequest communicationRequest) {
      if (communicationRequest.hasCategory()) {
        communicationRequest.getCategory().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (communicationRequest.hasReasonCode()) {
        communicationRequest.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (communicationRequest.hasPayload()) {
        communicationRequest.getPayload().forEach(payload -> {
          if (payload.hasContent() && payload.getContent() instanceof CodeableConcept content) {
            codes.addAll(content.getCoding());
          }
        });
      }
    } else if (resource instanceof MedicationRequest medRequest) {
      if (medRequest.hasMedicationCodeableConcept()) {
        codes.addAll(medRequest.getMedicationCodeableConcept().getCoding());
      } else if (medRequest.hasMedicationReference()) {
        Medication medication = ResourceResolver.resolveReference(medRequest.getMedicationReference(), Medication.class,
            medRequest, request);
        if (medication != null && medication.hasCode()) {
          codes.addAll(medication.getCode().getCoding());
        }
      }
    } else if (resource instanceof NutritionOrder nutritionOrder) {
      if (nutritionOrder.hasOralDiet() && nutritionOrder.getOralDiet().hasType()) {
        nutritionOrder.getOralDiet().getType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (nutritionOrder.hasSupplement()) {
        nutritionOrder.getSupplement().forEach(supplement -> {
          if (supplement.hasType()) {
            codes.addAll(supplement.getType().getCoding());
          }
        });
      }
      if (nutritionOrder.hasEnteralFormula()) {
        if (nutritionOrder.getEnteralFormula().hasBaseFormulaType()) {
          codes.addAll(nutritionOrder.getEnteralFormula().getBaseFormulaType().getCoding());
        }
        if (nutritionOrder.getEnteralFormula().hasAdditiveType()) {
          codes.addAll(nutritionOrder.getEnteralFormula().getAdditiveType().getCoding());
        }
      }
    } else if (resource instanceof ServiceRequest serviceRequest) {
      if (serviceRequest.hasCode()) {
        codes.addAll(serviceRequest.getCode().getCoding());
      }
    } else if (resource instanceof SupplyRequest supplyRequest) {
      if (supplyRequest.hasItemCodeableConcept()) {
        codes.addAll(supplyRequest.getItemCodeableConcept().getCoding());
      }
    } else if (resource instanceof VisionPrescription visionPrescription) {
      if (visionPrescription.hasLensSpecification()) {
        visionPrescription.getLensSpecification().forEach(spec -> {
          if (spec.hasProduct()) {
            codes.addAll(spec.getProduct().getCoding());
          }
        });
      }
    } else if (resource instanceof Appointment appointment) {
      if (appointment.hasServiceType()) {
        appointment.getServiceType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (appointment.hasReasonCode()) {
        appointment.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
    } else if (resource instanceof Encounter encounter) {
      if (encounter.hasClass_()) {
        codes.add(encounter.getClass_());
      }
      if (encounter.hasType()) {
        encounter.getType().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (encounter.hasServiceType()) {
        codes.addAll(encounter.getServiceType().getCoding());
      }
      if (encounter.hasReasonCode()) {
        encounter.getReasonCode().forEach(cc -> codes.addAll(cc.getCoding()));
      }
      if (encounter.hasHospitalization() && encounter.getHospitalization().hasDischargeDisposition()) {
        codes.addAll(encounter.getHospitalization().getDischargeDisposition().getCoding());
      }
    }

    if (normalizeSystem) {
      codes.forEach(coding -> {
        if (coding.hasSystem() && coding.getSystem().startsWith("https://")) {
          String alt = ResourceResolver.getAlternateProtocolUrl(coding.getSystem());
          if (alt != null) {
            coding.setSystem(alt);
          }
        }
      });
    }

    return codes;
  }

  /**
   * Extracts the best display string from a CodeableConcept.
   * Prefers text, then coding display, then coding code.
   */
  public static String codeableConceptDisplay(CodeableConcept codeableConcept) {
    if (codeableConcept == null) {
      return null;
    }
    if (codeableConcept.hasText()) {
      return codeableConcept.getText();
    }
    if (codeableConcept.hasCoding()) {
      Coding coding = codeableConcept.getCodingFirstRep();
      if (coding.hasDisplay()) {
        return coding.getDisplay();
      }
      if (coding.hasCode()) {
        return coding.getCode();
      }
    }
    return null;
  }
}
