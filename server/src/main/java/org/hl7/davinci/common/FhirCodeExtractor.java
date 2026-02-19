package org.hl7.davinci.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Medication;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Substance;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.VisionPrescription;

/**
 * Static utility for extracting FHIR codes from order and context resources.
 */
public final class FhirCodeExtractor {

  private FhirCodeExtractor() {
  }

  /**
   * Extracts codes without a pre-resolved Medication (references cannot be resolved).
   */
  public static List<Coding> extractCodes(Resource resource, boolean normalizeSystem) {
    return extractCodes(resource, normalizeSystem, null);
  }

  /**
   * Extracts codes from context resources (orders, appointments, encounters, etc.).
   * Optionally normalizes code systems from https:// to http:// for consistent matching.
   *
   * @param resource             the resource to extract codes from
   * @param normalizeSystem      whether to normalize https:// systems to http://
   * @param resolvedItemResource pre-resolved resource for orders with item references
   *                             (MedicationRequest.medicationReference or SupplyRequest.itemReference), or null
   */
  public static List<Coding> extractCodes(Resource resource, boolean normalizeSystem, Resource resolvedItemResource) {
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
        addCodesFromResolvedResource(codes, resolvedItemResource);
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
      } else if (supplyRequest.hasItemReference()) {
        addCodesFromResolvedResource(codes, resolvedItemResource);
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
          String alt = FhirUtil.getAlternateProtocolUrl(coding.getSystem());
          if (alt != null) {
            coding.setSystem(alt);
          }
        }
      });
    }

    return codes;
  }

  /**
   * Resolves the referenced item for MedicationRequest/SupplyRequest orders.
   * Handles inline resources and empty references before calling the resolver.
   */
  public static Resource resolveReferencedItem(Resource order, Function<Reference, Resource> resolver) {
    Reference itemRef = extractReferencedItem(order);
    if (itemRef == null) {
      return null;
    }

    if (itemRef.getResource() instanceof Resource inlineResource) {
      return inlineResource;
    }

    if (!itemRef.hasReference() || resolver == null) {
      return null;
    }

    return resolver.apply(itemRef);
  }

  /**
   * Returns the item reference used by order resources that support referenced items.
   */
  public static Reference extractReferencedItem(Resource order) {
    if (order instanceof MedicationRequest medRequest && medRequest.hasMedicationReference()) {
      return medRequest.getMedicationReference();
    }
    if (order instanceof SupplyRequest supplyRequest && supplyRequest.hasItemReference()) {
      return supplyRequest.getItemReference();
    }
    return null;
  }

  private static void addCodesFromResolvedResource(List<Coding> codes, Resource resolved) {
    if (resolved instanceof Medication med && med.hasCode()) {
      codes.addAll(med.getCode().getCoding());
    } else if (resolved instanceof Substance sub && sub.hasCode()) {
      codes.addAll(sub.getCode().getCoding());
    } else if (resolved instanceof Device dev && dev.hasType()) {
      codes.addAll(dev.getType().getCoding());
    }
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
