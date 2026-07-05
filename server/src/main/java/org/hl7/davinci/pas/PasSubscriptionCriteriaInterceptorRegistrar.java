package org.hl7.davinci.pas;

import ca.uhn.fhir.IHapiBootOrder;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
public class PasSubscriptionCriteriaInterceptorRegistrar {

  private final IInterceptorService myInterceptorService;
  private final PasSubscriptionCriteriaInterceptor myInterceptor;

  public PasSubscriptionCriteriaInterceptorRegistrar(
      IInterceptorService theInterceptorService,
      PasSubscriptionCriteriaInterceptor theInterceptor) {
    myInterceptorService = theInterceptorService;
    myInterceptor = theInterceptor;
  }

  @EventListener(classes = {ContextRefreshedEvent.class})
  @Order(IHapiBootOrder.REGISTER_INTERCEPTORS)
  public void register() {
    myInterceptorService.registerInterceptor(myInterceptor);
  }
}
