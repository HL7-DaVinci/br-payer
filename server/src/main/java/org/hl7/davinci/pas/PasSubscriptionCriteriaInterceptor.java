package org.hl7.davinci.pas;

import java.util.regex.Pattern;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Subscription;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;

/**
 * Validates and normalizes PAS subscription filter criteria before storage.
 */
@Interceptor
@Component
public class PasSubscriptionCriteriaInterceptor {

  private static final String FILTER_EXT =
      "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-filter-criteria";
  private static final String PAS_TOPIC =
      "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic";
  private static final Pattern VALID_FILTER =
      Pattern.compile("^(Bundle\\?)?orgIdentifier=[^&?]+$");

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_CREATED)
  public void created(IBaseResource resource) {
    ifSubscription(resource);
  }

  @Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
  public void updated(IBaseResource oldResource, IBaseResource newResource) {
    ifSubscription(newResource);
  }

  private void ifSubscription(IBaseResource resource) {
    if (resource instanceof Subscription sub && isPasTopic(sub)) {
      normalize(sub);
    }
  }

  private boolean isPasTopic(Subscription sub) {
    String criteria = sub.getCriteria();
    return criteria != null
        && (criteria.equals(PAS_TOPIC) || criteria.startsWith(PAS_TOPIC + "|"));
  }

  void normalize(Subscription sub) {
    Extension filter = sub.getCriteriaElement().getExtensionByUrl(FILTER_EXT);
    String value = filter != null && filter.getValue() instanceof StringType stringValue
        ? stringValue.getValue()
        : null;
    if (value == null || !VALID_FILTER.matcher(value).matches()) {
      throw new UnprocessableEntityException(
          "PAS subscription filter criteria must be 'orgIdentifier=<sending system identifier>' per PAS 2.2.1 spec-58; got: "
              + value);
    }
    if (!value.startsWith("Bundle?")) {
      // HAPI's backport filter parser requires the 'ResourceType?param=value' shape;
      // the IG canonical form has no prefix, so normalize before HAPI parses it.
      filter.setValue(new StringType("Bundle?" + value));
    }
  }
}
