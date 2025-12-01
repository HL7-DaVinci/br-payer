package org.hl7.davinci.cdshooks;

import org.opencds.cqf.fhir.cr.hapi.common.IPlanDefinitionProcessorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;
import jakarta.annotation.PostConstruct;

@Configuration
public class CdsHooksConfig {

  private static final Logger logger = LoggerFactory.getLogger(CdsHooksConfig.class);
  
  @Autowired
  private CdsHooksContextBooter booter;

  @Autowired
  private AppProperties appProperties;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

  @PostConstruct
  public void registerCdsServices() {
    logger.info("Registering CDS Services with parent context");

    CdsServiceCtx.setAppProperties(appProperties);
    CdsServiceCtx.setDaoRegistry(daoRegistry);
    CdsServiceCtx.setPlanDefinitionProcessorFactory(planDefinitionProcessorFactory);

    booter.setDefinitionsClass(CdsServiceCtx.class);
    booter.start();
    booter.buildCdsServiceCache();

  }

}
