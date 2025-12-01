package org.hl7.davinci.cql;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Library;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Resolves CQL file content when a Library resource references an external 
 * CQL file via the content.url field.
 * 
 * This allows developers to work with plain CQL files instead of
 * having to base64 encode them into the Library resource.
 * 
 * The CQL file must be in the same directory as the Library JSON file.
 * 
 * Usage:
 * 1. Place your CQL file next to the Library JSON file in the same directory
 * 2. Reference it in the Library's content.url field with just the filename:
 *    "content": [{ "contentType": "text/cql", "url": "MyLibrary.cql" }]
 * 3. The DataInitializer will load the Library and this resolver will
 *    automatically load the CQL content from the same directory.
 */
@Component
public class CqlFileResolver {

    private static final Logger logger = LoggerFactory.getLogger(CqlFileResolver.class);

    @Autowired
    private ResourceLoader resourceLoader;

    /**
     * Resolves external CQL file references in a Library resource's content.
     * If content.url is set and content.data is empty, attempts to load
     * the CQL file from the same directory as the Library JSON file.
     * After successfully loading the content, the url property is removed
     * so the Library resource only contains the embedded data.
     * 
     * This method is optional - Library resources that already have base64
     * encoded data in the content.data field will be left unchanged.
     *
     * @param library the Library resource to resolve
     * @param libraryResource the Spring Resource for the Library JSON file
     * @return true if any content was resolved, false otherwise
     */
    public boolean resolveExternalContent(Library library, Resource libraryResource) {
        boolean resolved = false;
        
        for (Attachment content : library.getContent()) {
            // Skip if data is already present (base64 encoded content)
            if (content.hasData()) {
                logger.debug("Library/{} already has embedded data, skipping URL resolution", 
                    library.getId());
                continue;
            }
            
            // Only process if URL is present and data is not
            if (content.hasUrl()) {
                String url = content.getUrl();
                
                // Skip absolute URLs
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    logger.debug("Skipping absolute URL: {}", url);
                    continue;
                }
                
                // Resolve the CQL file relative to the Library JSON file
                try {
                    Resource cqlResource = resolveRelativeResource(libraryResource, url);
                    
                    if (cqlResource != null && cqlResource.exists()) {
                        try (InputStream is = cqlResource.getInputStream()) {
                            byte[] fileBytes = is.readAllBytes();
                            content.setData(fileBytes);
                            // Remove the url property now that data is embedded
                            content.setUrlElement(null);
                            logger.info("Resolved CQL content from {} for Library/{}", 
                                url, library.getId());
                            resolved = true;
                        }
                    } else {
                        logger.warn("CQL file not found: {} for Library/{}", 
                            url, library.getId());
                    }
                } catch (IOException e) {
                    logger.error("Error reading CQL file {} for Library/{}: {}", 
                        url, library.getId(), e.getMessage());
                }
            }
        }
        
        return resolved;
    }

    /**
     * Resolves a resource path relative to another resource.
     * Works for both filesystem and JAR-based resources.
     */
    private Resource resolveRelativeResource(Resource baseResource, String relativePath) throws IOException {
        try {
            URI baseUri = baseResource.getURI();
            String baseUriStr = baseUri.toString();
            
            // Get the parent directory URI
            int lastSlash = baseUriStr.lastIndexOf('/');
            if (lastSlash > 0) {
                String parentUri = baseUriStr.substring(0, lastSlash + 1);
                String resolvedUri = parentUri + relativePath;
                return resourceLoader.getResource(resolvedUri);
            }
        } catch (IOException e) {
            logger.debug("Could not resolve relative resource via URI: {}", e.getMessage());
        }
        
        return null;
    }
}