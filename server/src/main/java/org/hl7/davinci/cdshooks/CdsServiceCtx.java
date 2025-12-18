package org.hl7.davinci.cdshooks;

import java.util.ArrayList;
import java.util.List;

import org.hl7.davinci.cdshooks.services.OrderSelectService;
import org.hl7.davinci.cdshooks.services.OrderSignService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration to register CDS services.
 */
@Configuration
public class CdsServiceCtx {

   @Bean
   public OrderSignService orderSignService() {
      return new OrderSignService();
   }

   @Bean
   public OrderSelectService orderSelectService() {
      return new OrderSelectService();
   }

   @Bean
   public List<Object> cdsServices(OrderSignService orderSignService, OrderSelectService orderSelectService) {
      List<Object> services = new ArrayList<>();
      services.add(orderSignService);
      services.add(orderSelectService);
      return services;
   }

}
