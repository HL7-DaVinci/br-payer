package org.hl7.davinci.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.interceptor.BasicAuthInterceptor;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.hl7.fhir.common.hapi.validation.support.BaseValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.ValueSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fetches VSAC-hosted ValueSets on demand with UMLS API key authentication.
 * Only intercepts ValueSet URLs matching the VSAC canonical prefix; all other
 * terminology resolution falls through to other mechanisms.
 * Activated only when vsac.api-key is set (e.g. via VSAC_API_KEY env var).
 */
@Configuration
@ConditionalOnExpression("!'${vsac.api-key:}'.isEmpty()")
@EnableConfigurationProperties(VsacProperties.class)
public class VsacTerminologyConfig {

    private static final Logger logger = LoggerFactory.getLogger(VsacTerminologyConfig.class);

    private static final String VSAC_VALUESET_PREFIX = "http://cts.nlm.nih.gov/fhir/ValueSet/";

    @Bean
    public IValidationSupport vsacValidationSupport(
            FhirContext fhirContext, ValidationSupportChain validationSupportChain,
            VsacProperties vsacProperties) {

        IGenericClient client = fhirContext.newRestfulGenericClient(vsacProperties.url());
        client.registerInterceptor(new BasicAuthInterceptor("apikey", vsacProperties.apiKey()));

        var support = new VsacValidationSupport(fhirContext, client);
        validationSupportChain.addValidationSupport(0, support);
        return support;
    }

    static class VsacValidationSupport extends BaseValidationSupport {

        private final IGenericClient client;

        VsacValidationSupport(FhirContext fhirContext, IGenericClient client) {
            super(fhirContext);
            this.client = client;
        }

        @Override
        public IBaseResource fetchValueSet(String theValueSetUrl) {
            if (theValueSetUrl == null || !theValueSetUrl.startsWith(VSAC_VALUESET_PREFIX)) {
                return null;
            }

            String oid = theValueSetUrl.substring(VSAC_VALUESET_PREFIX.length());
            try {
                logger.info("Fetching ValueSet from VSAC: {}", theValueSetUrl);
                return client.read().resource(ValueSet.class).withId(oid).execute();
            } catch (ResourceNotFoundException e) {
                logger.warn("ValueSet not found in VSAC: {}", theValueSetUrl);
                return null;
            } catch (Exception e) {
                logger.error("Failed to fetch ValueSet from VSAC: {}", theValueSetUrl, e);
                return null;
            }
        }
    }
}
