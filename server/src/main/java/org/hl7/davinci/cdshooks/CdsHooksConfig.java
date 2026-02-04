package org.hl7.davinci.cdshooks;

import org.hl7.davinci.cdshooks.shared.CrdPrefetchSvc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.hapi.fhir.cdshooks.api.ICdsHooksDaoAuthorizationSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchDaoSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsPrefetchFhirClientSvc;
import ca.uhn.hapi.fhir.cdshooks.svc.prefetch.CdsResolutionStrategySvc;

/**
 * Configuration for CDS Hooks services with access to parent context.
 */
@Configuration
public class CdsHooksConfig {

  private final Logger logger = LoggerFactory.getLogger(CdsHooksConfig.class);

  @Autowired
  private ApplicationContext applicationContext;

  /**
   * Creates a parent-aware CdsHooksContextBooter.
   * The @Primary annotation ensures this bean overrides the default from
   * StarterCdsHooksConfig.
   */
  @Bean
  @Primary
  public CdsHooksContextBooter cdsHooksContextCustomBooter() {

    logger.info("Creating custom booter for CDS hooks with parent ApplicationContext.");

    CdsHooksContextCustomBooter booter = new CdsHooksContextCustomBooter();
    booter.setParentContext(applicationContext);
    booter.setDefinitionsClass(CdsServiceCtx.class);
    booter.start();

    return booter;
  }

  /**
   * Custom prefetch service that handles optional context variables gracefully.
   * Overrides the default to skip OMIT prefetch templates with missing context
   * variables while delegating to the base HAPI prefetch handling.
   * 
   * Marking this as @Primary and giving it our own name to ensure it replaces the
   * "cdsPrefetchSvc" bean from the HAPI library without requiring a
   * BeanPostProcessor.
   */
  @Bean
  @Primary
  public CrdPrefetchSvc crdPrefetchSvc(
      CdsResolutionStrategySvc cdsResolutionStrategySvc,
      CdsPrefetchDaoSvc cdsPrefetchDaoSvc,
      CdsPrefetchFhirClientSvc cdsPrefetchFhirClientSvc,
      ICdsHooksDaoAuthorizationSvc cdsHooksDaoAuthorizationSvc,
      IInterceptorBroadcaster interceptorBroadcaster) {
    return new CrdPrefetchSvc(
        cdsResolutionStrategySvc,
        cdsPrefetchDaoSvc,
        cdsPrefetchFhirClientSvc,
        cdsHooksDaoAuthorizationSvc,
        interceptorBroadcaster);
  }

  /**
   * Removes the cdsServiceInterceptor bean definition from the library to prevent
   * automatic CDS service registration from PlanDefinitions. This server registers
   * CDS services explicitly via CdsServiceCtx.
   */
  @Bean
  static BeanDefinitionRegistryPostProcessor removeCdsServiceInterceptor() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (registry.containsBeanDefinition("cdsServiceInterceptor")) {
          registry.removeBeanDefinition("cdsServiceInterceptor");
          LoggerFactory.getLogger(CdsHooksConfig.class)
              .info("Removed cdsServiceInterceptor bean - using explicit CDS service registration");
        }
      }
    };
  }

}
