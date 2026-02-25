package org.hl7.davinci.datainitializer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hl7.davinci.cql.CqlFileResolver;
import org.hl7.davinci.cql.DaoLibrarySourceProvider;
import org.hl7.davinci.cql.ElmCompiler;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

class DataInitializerTest {

  private DataInitializer initializer;
  private DaoRegistry daoRegistry;
  private IFhirResourceDao<IBaseResource> resourceDao;
  private DataInitializerProperties properties;
  private ResourceLoader resourceLoader;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    initializer = new DataInitializer();
    daoRegistry = mock(DaoRegistry.class);
    resourceDao = mock(IFhirResourceDao.class);
    properties = new DataInitializerProperties();
    resourceLoader = mock(ResourceLoader.class);

    ReflectionTestUtils.setField(initializer, "fhirContext", FhirContext.forR4Cached());
    ReflectionTestUtils.setField(initializer, "daoRegistry", daoRegistry);
    ReflectionTestUtils.setField(initializer, "dataInitializerProperties", properties);
    ReflectionTestUtils.setField(initializer, "resourceLoader", resourceLoader);
    ReflectionTestUtils.setField(initializer, "cqlFileResolver", mock(CqlFileResolver.class));
    ReflectionTestUtils.setField(initializer, "elmCompiler", mock(ElmCompiler.class));
    ReflectionTestUtils.setField(initializer, "daoLibrarySourceProvider", mock(DaoLibrarySourceProvider.class));

    when(daoRegistry.getResourceDao(any(IBaseResource.class))).thenReturn(resourceDao);
  }

  @Test
  void initializeData_doesNothingWhenInitialDataPropertyMissing() {
    properties.setInitialData(null);

    initializer.initializeData();

    verify(resourceLoader, never()).getResource(any());
  }

  @Test
  void loadResourcesWithRetry_retriesDeferredResourceUntilLaterPassSucceeds() {
    var first = jsonResource("Patient-first.json",
        "{\"resourceType\":\"Patient\",\"id\":\"first\"}");
    var second = jsonResource("Patient-second.json",
        "{\"resourceType\":\"Patient\",\"id\":\"second\"}");
    AtomicInteger secondAttempts = new AtomicInteger(0);

    doAnswer(invocation -> {
      IBaseResource resource = invocation.getArgument(0);
      String id = ((Resource) resource).getIdElement().getIdPart();
      if ("second".equals(id) && secondAttempts.getAndIncrement() == 0) {
        throw new RuntimeException("dependency missing");
      }
      return null;
    }).when(resourceDao).update(any(IBaseResource.class), any(SystemRequestDetails.class));

    ReflectionTestUtils.invokeMethod(
        initializer,
        "loadResourcesWithRetry",
        List.of(first, second),
        "test-dir");

    verify(resourceDao, atLeastOnce())
        .update(any(IBaseResource.class), any(SystemRequestDetails.class));
  }

  private ByteArrayResource jsonResource(String filename, String json) {
    return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return filename;
      }
    };
  }
}
