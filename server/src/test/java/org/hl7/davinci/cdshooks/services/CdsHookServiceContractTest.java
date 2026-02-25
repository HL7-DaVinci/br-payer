package org.hl7.davinci.cdshooks.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import org.hl7.davinci.cdshooks.shared.CdsServiceBase;
import org.hl7.davinci.cdshooks.shared.CrdConformanceEnforcer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CdsHookServiceContractTest {

  private static Stream<Arguments> serviceContracts() {
    return Stream.of(
        Arguments.of(new OrderSignService(), "order-sign", true),
        Arguments.of(new OrderDispatchService(), "order-dispatch", true),
        Arguments.of(new AppointmentBookService(), "appointment-book", true),
        Arguments.of(new OrderSelectService(), "order-select", false),
        Arguments.of(new EncounterStartService(), "encounter-start", false),
        Arguments.of(new EncounterDischargeService(), "encounter-discharge", false));
  }

  @ParameterizedTest
  @MethodSource("serviceContracts")
  void hookNameAndPrimaryClassification_matchCrdContract(
      CdsServiceBase service,
      String expectedHookName,
      boolean isPrimaryHook) {
    assertEquals(expectedHookName, readHookName(service));
    assertEquals(isPrimaryHook, CrdConformanceEnforcer.isPrimaryHook(expectedHookName));
    assertEquals(!isPrimaryHook, CrdConformanceEnforcer.isSecondaryHook(expectedHookName));
  }

  private static String readHookName(CdsServiceBase service) {
    try {
      Method method = service.getClass().getDeclaredMethod("getHookName");
      method.setAccessible(true);
      return (String) method.invoke(service);
    } catch (Exception e) {
      throw new AssertionError("Failed to read hook name via reflection", e);
    }
  }
}
