package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DeviceRequest;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Test;

class BundleResourceUtilTest {

  @Test
  void addByUnqualifiedVersionlessIdentity_deduplicatesSameResourceIdentity() {
    Bundle bundle = new Bundle();
    Set<String> seen = new HashSet<>();

    DeviceRequest first = new DeviceRequest();
    first.setId("DeviceRequest/dr-1");
    DeviceRequest duplicate = new DeviceRequest();
    duplicate.setId("DeviceRequest/dr-1/_history/2");

    assertTrue(BundleResourceUtil.addByUnqualifiedVersionlessIdentity(bundle, seen, first));
    assertFalse(BundleResourceUtil.addByUnqualifiedVersionlessIdentity(bundle, seen, duplicate));
    assertEquals(1, bundle.getEntry().size());
  }

  @Test
  void addByVersionlessIdentity_addsWhenIdentityNotSeen() {
    Bundle bundle = new Bundle();
    Set<String> seen = new HashSet<>();

    Patient patient = new Patient();
    patient.setId("Patient/p-1");

    assertTrue(BundleResourceUtil.addByVersionlessIdentity(bundle, seen, patient));
    assertEquals(1, bundle.getEntry().size());
  }

  @Test
  void addByVersionlessIdentity_allowsResourcesWithoutIds() {
    Bundle bundle = new Bundle();
    Set<String> seen = new HashSet<>();

    Patient patient = new Patient();
    assertTrue(BundleResourceUtil.addByVersionlessIdentity(bundle, seen, patient));
    assertEquals(1, bundle.getEntry().size());
  }
}
