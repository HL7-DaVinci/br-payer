package org.hl7.davinci.cdshooks;

import org.hl7.davinci.cdshooks.shared.CdsPrefetchInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;

/**
 * Configuration for CDS Hooks services with access to parent context.
 */
@Configuration
public class CdsHooksConfig {

  private final Logger logger = LoggerFactory.getLogger(CdsHooksConfig.class);

  @Autowired
  private ApplicationContext applicationContext;

  @Autowired
  private IInterceptorService interceptorService;

  /**
   * Creates a parent-aware CdsHooksContextBooter.
   * The @Primary annotation ensures this bean overrides the default from StarterCdsHooksConfig.
   */
  @Bean
  @Primary
  public CdsHooksContextBooter cdsHooksContextCustomBooter() {

    logger.info("Creating custom booter for CDS hooks with parent ApplicationContext.");
    interceptorService.registerInterceptor(new CdsPrefetchInterceptor());
    
    CdsHooksContextCustomBooter booter = new CdsHooksContextCustomBooter();
    booter.setParentContext(applicationContext);
    booter.setDefinitionsClass(CdsServiceCtx.class);
    booter.start();
    
    return booter;
  }

}
