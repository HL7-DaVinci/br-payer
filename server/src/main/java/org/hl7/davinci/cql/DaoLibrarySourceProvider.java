package org.hl7.davinci.cql;

import java.util.Set;

import org.cqframework.cql.cql2elm.LibrarySourceProvider;
import org.hl7.elm.r1.VersionedIdentifier;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.TokenParam;
import kotlinx.io.Buffer;
import kotlinx.io.Source;

/**
 * Resolves CQL include dependencies by looking up Library resources from the
 * FHIR repository. Implements the CQL translator's LibrarySourceProvider
 * interface for use during ELM compilation.
 */
@Component
public class DaoLibrarySourceProvider implements LibrarySourceProvider {

  private static final Logger logger = LoggerFactory.getLogger(DaoLibrarySourceProvider.class);
  private static final String CQL_CONTENT_TYPE = "text/cql";

  // Libraries bundled on the classpath (resolved by the translator's built-in FhirLibrarySourceProvider)
  private static final Set<String> CLASSPATH_LIBRARIES = Set.of("FHIRHelpers");

  /** Check if a library name is provided on the classpath (not stored in the repository). */
  public static boolean isClasspathLibrary(String name) {
    return name != null && CLASSPATH_LIBRARIES.contains(name);
  }

  private final DaoRegistry daoRegistry;

  public DaoLibrarySourceProvider(DaoRegistry daoRegistry) {
    this.daoRegistry = daoRegistry;
  }

  @Override
  public Source getLibrarySource(VersionedIdentifier libraryIdentifier) {
    String name = libraryIdentifier.getId();
    String version = libraryIdentifier.getVersion();

    if (CLASSPATH_LIBRARIES.contains(name)) {
      return null;
    }

    SearchParameterMap searchParams = new SearchParameterMap();
    searchParams.add("name", new StringParam(name).setExact(true));
    if (version != null) {
      searchParams.add("version", new TokenParam(version));
    }

    IBundleProvider results = daoRegistry
        .getResourceDao(Library.class)
        .search(searchParams, new SystemRequestDetails());

    if (results.isEmpty()) {
      logger.debug("Library not found in repository: {} version {}", name, version);
      return null;
    }

    Library library = (Library) results.getResources(0, 1).get(0);

    Attachment cqlAttachment = library.getContent().stream()
        .filter(c -> CQL_CONTENT_TYPE.equals(c.getContentType()) && c.hasData())
        .findFirst()
        .orElse(null);

    if (cqlAttachment == null) {
      logger.debug("Library/{} has no CQL content attachment", library.getId());
      return null;
    }

    byte[] cqlBytes = cqlAttachment.getData();
    Buffer buffer = new Buffer();
    buffer.write(cqlBytes, 0, cqlBytes.length);
    return buffer;
  }
}
