package org.hl7.davinci.cdshooks;

import java.util.List;

import org.hl7.davinci.cdshooks.services.AppointmentBookService;
import org.hl7.davinci.cdshooks.services.OrderDispatchService;
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
   public AppointmentBookService appointmentBookService() {
      return new AppointmentBookService();
   }

   @Bean
   public OrderDispatchService orderDispatchService() {
      return new OrderDispatchService();
   }

   @Bean
   public OrderSelectService orderSelectService() {
      return new OrderSelectService();
   }

   @Bean
   public OrderSignService orderSignService() {
      return new OrderSignService();
   }

   @Bean
   public List<Object> cdsServices(
         AppointmentBookService appointmentBookService,
         OrderDispatchService orderDispatchService,
         OrderSelectService orderSelectService,
         OrderSignService orderSignService) {
      return List.of(appointmentBookService, orderDispatchService, orderSelectService, orderSignService);
   }

}

