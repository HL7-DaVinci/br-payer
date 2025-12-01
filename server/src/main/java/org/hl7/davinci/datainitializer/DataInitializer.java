package org.hl7.davinci.datainitializer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.hl7.davinci.cql.CqlFileResolver;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import jakarta.annotation.PostConstruct;

/**
 * Loads initial FHIR resources from the classpath at server startup.
 * 
 * Resources are loaded from directories specified in the 'initial-data' configuration.
 * Each directory should contain JSON files representing FHIR resources.
 * 
 * For Library resources with CQL content, the CQL can be:
 * - Embedded as base64 in content.data (traditional approach)
 * - Referenced via content.url pointing to a .cql file in the same directory
 *   (the CQL will be automatically loaded and embedded at startup)
 * 
 * During development, external directories (like 'library/') can be added to the
 * classpath. For production builds, these resources are bundled into the JAR.
 */
@Configuration
@Conditional(NonEmptyInitialDataCondition.class)
public class DataInitializer {

  private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

  @Autowired
  private FhirContext fhirContext;

  @Autowired
  private DaoRegistry daoRegistry;

  @Autowired
  private DataInitializerProperties dataInitializerProperties;

  @Autowired
  private ResourceLoader resourceLoader;

  @Autowired
  private CqlFileResolver cqlFileResolver;

  @PostConstruct
  public void initializeData() {
    if (dataInitializerProperties.getInitialData() == null || dataInitializerProperties.getInitialData().isEmpty()) {
      return;
    }

    logger.info("Initializing data");

    for (String directoryPath : dataInitializerProperties.getInitialData()) {
      loadFromClasspath(directoryPath);
    }
  }

  /**
   * Load resources from the classpath.
   */
  private void loadFromClasspath(String directoryPath) {
    logger.info("Loading resources from classpath: {}", directoryPath);

    Resource[] resources;
    try {
      resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader)
          .getResources("classpath:" + directoryPath + "/**/*.json");
    } catch (Exception e) {
      logger.error("Error loading resources from classpath: {}", directoryPath, e);
      return;
    }

    if (resources.length == 0) {
      logger.warn("No JSON resources found in classpath: {}", directoryPath);
      return;
    }

    // Use Spring Resources directly (works with both filesystem and JAR)
    List<Resource> jsonResources = new ArrayList<>();
    for (Resource resource : resources) {
      if (resource.exists()) {
        jsonResources.add(resource);
      } else {
        logger.warn("Resource does not exist: {}", resource.getFilename());
      }
    }

    loadResourcesWithRetry(jsonResources, directoryPath);
  }

  /**
   * Load resources with retry logic to handle dependency ordering.
   */
  private void loadResourcesWithRetry(List<Resource> jsonResources, String directoryPath) {
    List<Resource> queue = new ArrayList<>(jsonResources);
    int pass = 0;
    int loadedTotal = 0;

    while (!queue.isEmpty()) {
      pass++;
      int loadedThisPass = 0;

      Iterator<Resource> it = queue.iterator();
      while (it.hasNext()) {
        Resource jsonResource = it.next();
        try (InputStream is = jsonResource.getInputStream()) {
          String resourceText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
          IBaseResource fhirResource = fhirContext.newJsonParser().parseResource(resourceText);

          // For Library resources, resolve external CQL file references
          if (fhirResource instanceof Library library) {
            cqlFileResolver.resolveExternalContent(library, jsonResource);
          }

          IFhirResourceDao<IBaseResource> dao = daoRegistry.getResourceDao(fhirResource);
          dao.update(fhirResource, new SystemRequestDetails());
          it.remove();
          loadedThisPass++;
          loadedTotal++;
          logger.debug("Loaded resource: {} (pass {})", jsonResource.getFilename(), pass);
        } catch (Exception e) {
          // Defer and try again in the next pass
          logger.trace("Deferring resource {} until dependencies exist: {}",
              jsonResource.getFilename(), e.getMessage());
        }
      }

      logger.info("Pass {} complete. Loaded {} resources ({} remaining).", pass, loadedThisPass, queue.size());

      if (loadedThisPass == 0) {
        // No progress made; break to avoid infinite loop and report failures.
        for (Resource remaining : queue) {
          logger.warn("Failed to load resource after {} passes: {}", pass, remaining.getFilename());
        }
        break;
      }
    }
    logger.info("Finished loading classpath directory {}. Loaded {} resources.", directoryPath, loadedTotal);
  }
}