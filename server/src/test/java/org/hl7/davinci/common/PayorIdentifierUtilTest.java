package org.hl7.davinci.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Organization;
import org.junit.jupiter.api.Test;

class PayorIdentifierUtilTest {

  @Test
  void extractFromCoverageAndOrganizations_returnsOnlyValidIdentifiers() {
    Coverage coverage = new Coverage();
    coverage.addPayor().setReference("Organization/org-1");

    Organization org = new Organization();
    org.setId("org-1");
    org.addIdentifier().setSystem("sys").setValue("val");
    org.addIdentifier().setSystem("sys-only");

    List<Identifier> identifiers = PayorIdentifierUtil.extractFromCoverageAndOrganizations(
        coverage, List.of(org));

    assertEquals(1, identifiers.size());
    assertEquals("sys", identifiers.get(0).getSystem());
    assertEquals("val", identifiers.get(0).getValue());
  }

  @Test
  void extractFirstFromCoverageAndBundle_returnsIdentifiersForFirstResolvablePayor() {
    Coverage coverage = new Coverage();
    coverage.addPayor().setReference("Organization/org-1");

    Organization org = new Organization();
    org.setId("org-1");
    org.addIdentifier().setSystem("sys").setValue("val");

    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    bundle.addEntry().setResource(org);

    List<Identifier> identifiers = PayorIdentifierUtil.extractFirstFromCoverageAndBundle(coverage, bundle);

    assertEquals(1, identifiers.size());
    assertEquals("val", identifiers.get(0).getValue());
  }

  @Test
  void extractFromCoverageAndOrganizations_resolvesContainedPayorOrganization() {
    Organization contained = new Organization();
    contained.setId("OrgExample");
    contained.addIdentifier().setSystem("urn:oid:2.16.840.1.113883.4.7").setValue("10D0202020");

    Coverage coverage = new Coverage();
    coverage.addContained(contained);
    coverage.addPayor().setReference("#OrgExample");

    List<Identifier> identifiers = PayorIdentifierUtil.extractFromCoverageAndOrganizations(
        coverage, List.of(contained));

    assertEquals(1, identifiers.size());
    assertEquals("urn:oid:2.16.840.1.113883.4.7", identifiers.get(0).getSystem());
    assertEquals("10D0202020", identifiers.get(0).getValue());
  }

  @Test
  void addValidIdentifiers_ignoresNullInputs() {
    PayorIdentifierUtil.addValidIdentifiers(null, null);
    assertTrue(PayorIdentifierUtil.validIdentifiers(null).isEmpty());
  }
}
