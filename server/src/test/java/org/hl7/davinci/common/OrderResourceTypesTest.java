package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.VisionPrescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderResourceTypesTest {

  @Test
  @DisplayName("Supported order types are centralized and recognized consistently")
  void supportedTypes_areRecognizedConsistently() {
    Set<String> expected = Set.of(
        "Appointment",
        "CommunicationRequest",
        "DeviceRequest",
        "Encounter",
        "MedicationRequest",
        "NutritionOrder",
        "ServiceRequest",
        "SupplyRequest",
        "VisionPrescription");

    assertEquals(expected, OrderResourceTypes.supportedTypes());

    for (String type : OrderResourceTypes.supportedTypes()) {
      Resource resource = createResource(type);
      assertTrue(OrderResourceTypes.isSupported(type));
      assertNotNull(OrderResourceTypes.resourceClassFor(type));
      assertTrue(OrderResourceTypes.isSupported(resource));
      assertTrue(ResourceResolver.isOrderResource(resource));
    }
  }

  @Test
  @DisplayName("Unsupported resource types are rejected")
  void unsupportedTypes_areRejected() {
    Patient patient = new Patient();

    assertFalse(OrderResourceTypes.isSupported("Patient"));
    assertNull(OrderResourceTypes.resourceClassFor("Patient"));
    assertFalse(OrderResourceTypes.isSupported(patient));
    assertFalse(ResourceResolver.isOrderResource(patient));
  }

  private Resource createResource(String type) {
    return switch (type) {
      case "Appointment" -> new Appointment();
      case "CommunicationRequest" -> new CommunicationRequest();
      case "DeviceRequest" -> new DeviceRequest();
      case "Encounter" -> new Encounter();
      case "MedicationRequest" -> new MedicationRequest();
      case "NutritionOrder" -> new NutritionOrder();
      case "ServiceRequest" -> new ServiceRequest();
      case "SupplyRequest" -> new SupplyRequest();
      case "VisionPrescription" -> new VisionPrescription();
      default -> throw new IllegalArgumentException("Unsupported resource type in test: " + type);
    };
  }
}
