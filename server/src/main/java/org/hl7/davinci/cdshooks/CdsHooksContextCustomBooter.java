package org.hl7.davinci.cdshooks;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.hapi.fhir.cdshooks.svc.CdsHooksContextBooter;

/**
 * Extended CdsHooksContextBooter that supports a parent ApplicationContext.
 * Enables CDS service beans to autowire beans from the main HAPI FHIR servlet context.
 */
public class CdsHooksContextCustomBooter extends CdsHooksContextBooter {

    private static final Logger logger = LoggerFactory.getLogger(CdsHooksContextCustomBooter.class);

    private ApplicationContext parentContext;

    /**
     * Sets the parent ApplicationContext for bean autowiring.
     *
     * @param parentContext the parent Spring ApplicationContext
     */
    public void setParentContext(ApplicationContext parentContext) {
        this.parentContext = parentContext;
    }

    @Override
    public void start() {
        if (myDefinitionsClass == null) {
            logger.info("No application context defined");
            return;
        }
        logger.info("Starting Spring ApplicationContext for class: {} with parent context", myDefinitionsClass);

        myAppCtx = new AnnotationConfigApplicationContext();
        
        // Set parent context to enable bean inheritance
        if (parentContext != null) {
            myAppCtx.setParent(parentContext);
            logger.info("Parent context set - beans from parent will be available for autowiring");
        }
        
        myAppCtx.register(myDefinitionsClass);
        myAppCtx.refresh();

        try {
            if (myAppCtx.containsBean(CDS_SERVICES_BEAN_NAME)) {
                List<?> beans = myAppCtx.getBean(CDS_SERVICES_BEAN_NAME, java.util.List.class);
                myCdsServiceBeans = new ArrayList<>(beans);
            } else {
                logger.info("Context has no bean named {}", CDS_SERVICES_BEAN_NAME);
            }

            if (myCdsServiceBeans.isEmpty()) {
                throw new ConfigurationException(Msg.code(2379)
                        + "No CDS Services found in the context (need bean called " + CDS_SERVICES_BEAN_NAME + ")");
            }

        } catch (ConfigurationException e) {
            stop();
            throw e;
        } catch (Exception e) {
            stop();
            throw new ConfigurationException(Msg.code(2393) + e.getMessage(), e);
        }
    }
}
