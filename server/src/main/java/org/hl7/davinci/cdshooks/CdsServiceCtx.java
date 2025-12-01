package org.hl7.davinci.cdshooks;

import java.util.ArrayList;
import java.util.List;

import org.opencds.cqf.fhir.cr.hapi.common.IPlanDefinitionProcessorFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.starter.AppProperties;

@Configuration
public class CdsServiceCtx {

   private static AppProperties appProperties;
   private static DaoRegistry daoRegistry;
   private static IPlanDefinitionProcessorFactory planDefinitionProcessorFactory;

   public static void setAppProperties(AppProperties newAppProperties) {
      appProperties = newAppProperties;
   }
   
   public static void setDaoRegistry(DaoRegistry newDaoRegistry) {
      daoRegistry = newDaoRegistry;
   }

   public static void setPlanDefinitionProcessorFactory(IPlanDefinitionProcessorFactory newPlanDefinitionProcessorFactory) {
      planDefinitionProcessorFactory = newPlanDefinitionProcessorFactory;
   }

   @Bean
   public OrderSignService orderSignService() {
      return new OrderSignService(appProperties, daoRegistry, planDefinitionProcessorFactory);
   }

   @Bean
   public List<Object> cdsServices(OrderSignService orderSignService) {
      List<Object> services = new ArrayList<>();
      services.add(orderSignService);
      return services;
   }

}
