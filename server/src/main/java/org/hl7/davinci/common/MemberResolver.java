package org.hl7.davinci.common;

import java.util.List;
import java.util.Optional;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Coverage;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;

/**
 * Resolves a Coverage's beneficiary to a Patient in this payer's repository
 * by business identifier (Coverage.beneficiary.identifier, then
 * Coverage.subscriberId). Local FHIR ids supplied by senders are never used
 * as lookup keys.
 */
public final class MemberResolver {

  private static final Logger logger = LoggerFactory.getLogger(MemberResolver.class);

  private MemberResolver() {}

  /** Returns the locally stored Patient for this coverage, or empty if none can be confidently matched. */
  public static Optional<Patient> resolveMember(Coverage coverage, DaoRegistry daoRegistry) {
    if (coverage == null) {
      return Optional.empty();
    }
    if (coverage.hasBeneficiary() && coverage.getBeneficiary().hasIdentifier()) {
      Identifier id = coverage.getBeneficiary().getIdentifier();
      if (id.hasValue()) {
        Optional<Patient> match = findUnique(daoRegistry, id.getSystem(), id.getValue());
        if (match.isPresent()) {
          return match;
        }
      }
    }
    if (coverage.hasSubscriberId()) {
      return findUnique(daoRegistry, null, coverage.getSubscriberId());
    }
    return Optional.empty();
  }

  /** system=null means "match value against any Patient.identifier system" (used for Coverage.subscriberId). */
  private static Optional<Patient> findUnique(DaoRegistry daoRegistry, String system, String value) {
    SearchParameterMap params = new SearchParameterMap();
    params.setLoadSynchronous(true);
    params.add("identifier", new TokenParam(system, value));

    IBundleProvider results = daoRegistry.getResourceDao(Patient.class)
        .search(params, new SystemRequestDetails());
    List<IBaseResource> resources = results.getResources(0, 2);

    if (resources.isEmpty()) {
      return Optional.empty();
    }
    if (resources.size() > 1) {
      logger.warn("Multiple Patients matched identifier {}|{}; refusing to resolve to avoid ambiguity",
          system == null ? "*" : system, value);
      return Optional.empty();
    }
    return Optional.of((Patient) resources.get(0));
  }
}
