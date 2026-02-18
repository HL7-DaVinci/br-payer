package org.hl7.davinci.pas;

import static org.junit.jupiter.api.Assertions.*;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PasBundleValidatorTest {

  private PasBundleValidator validator;

  @BeforeEach
  void setUp() {
    validator = new PasBundleValidator();
  }

  @Test
  void validate_nullBundle_throws() {
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(null));
  }

  @Test
  void validate_wrongBundleType_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.TRANSACTION);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_emptyBundle_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_firstEntryNotClaim_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(new Patient());
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_claimNotPreauthorization_throws() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    Claim claim = new Claim();
    claim.setUse(Claim.Use.CLAIM);
    bundle.addEntry().setResource(claim);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_claimMissingPatient_throws() {
    Bundle bundle = buildMinimalBundle();
    ((Claim) bundle.getEntryFirstRep().getResource()).setPatient(null);
    assertThrows(IllegalArgumentException.class, () -> validator.validateSubmitBundle(bundle));
  }

  @Test
  void validate_validBundle_returnsClaim() {
    Bundle bundle = buildMinimalBundle();
    Claim result = validator.validateSubmitBundle(bundle);
    assertNotNull(result);
    assertEquals(Claim.Use.PREAUTHORIZATION, result.getUse());
  }

  @Test
  void validateInquiryBundle_validBundle_returnsClaim() {
    Bundle bundle = buildMinimalBundleWithMemberIdentifier();
    Claim result = validator.validateInquiryBundle(bundle);
    assertNotNull(result);
  }

  @Test
  void validateInquiryBundle_missingMemberIdentifier_throws() {
    Bundle bundle = buildMinimalBundle();  // no Patient resource in bundle with MB identifier
    assertThrows(IllegalArgumentException.class, () -> validator.validateInquiryBundle(bundle));
  }

  private Bundle buildMinimalBundle() {
    Claim claim = new Claim();
    claim.setUse(Claim.Use.PREAUTHORIZATION);
    claim.setPatient(new Reference("Patient/1"));
    claim.setInsurer(new Reference("Organization/1"));
    claim.setProvider(new Reference("PractitionerRole/1"));
    claim.addInsurance().setCoverage(new Reference("Coverage/1")).setFocal(true);
    claim.addItem().setSequence(1)
        .setProductOrService(new CodeableConcept().addCoding(
            new Coding("http://example.com", "99213", "Office Visit")));

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setFullUrl("urn:uuid:" + java.util.UUID.randomUUID()).setResource(claim);
    return bundle;
  }

  private Bundle buildMinimalBundleWithMemberIdentifier() {
    Bundle bundle = buildMinimalBundle();
    // Add Patient resource with MB identifier to bundle
    Patient patient = new Patient();
    patient.setId("1");
    patient.addIdentifier()
        .setType(new CodeableConcept().addCoding(
            new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "MB", "Member Number")))
        .setValue("MB123456");
    bundle.addEntry().setFullUrl("Patient/1").setResource(patient);
    return bundle;
  }
}
