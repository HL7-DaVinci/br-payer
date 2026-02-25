package org.hl7.davinci.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.Appointment;
import org.hl7.fhir.r4.model.CommunicationRequest;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.NutritionOrder;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.SupplyRequest;
import org.hl7.fhir.r4.model.VisionPrescription;

/**
 * Centralized definitions for resource types treated as "orders" across DTR/CRD flows.
 */
public final class OrderResourceTypes {

  private static final Map<String, Class<? extends Resource>> RESOURCE_CLASSES = buildResourceClasses();

  private OrderResourceTypes() {
  }

  public static Set<String> supportedTypes() {
    return RESOURCE_CLASSES.keySet();
  }

  public static boolean isSupported(String resourceType) {
    return resourceType != null && RESOURCE_CLASSES.containsKey(resourceType);
  }

  public static boolean isSupported(Resource resource) {
    return resource != null && isSupported(resource.fhirType());
  }

  public static Class<? extends Resource> resourceClassFor(String resourceType) {
    return RESOURCE_CLASSES.get(resourceType);
  }

  private static Map<String, Class<? extends Resource>> buildResourceClasses() {
    Map<String, Class<? extends Resource>> resourceClasses = new LinkedHashMap<>();
    resourceClasses.put("Appointment", Appointment.class);
    resourceClasses.put("CommunicationRequest", CommunicationRequest.class);
    resourceClasses.put("DeviceRequest", DeviceRequest.class);
    resourceClasses.put("Encounter", Encounter.class);
    resourceClasses.put("MedicationRequest", MedicationRequest.class);
    resourceClasses.put("NutritionOrder", NutritionOrder.class);
    resourceClasses.put("ServiceRequest", ServiceRequest.class);
    resourceClasses.put("SupplyRequest", SupplyRequest.class);
    resourceClasses.put("VisionPrescription", VisionPrescription.class);
    return Map.copyOf(resourceClasses);
  }
}
