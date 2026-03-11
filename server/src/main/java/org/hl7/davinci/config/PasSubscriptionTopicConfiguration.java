package org.hl7.davinci.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import ca.uhn.fhir.jpa.topic.SubscriptionTopicConfig;

/**
 * Enables HAPI's topic-based subscription infrastructure for R4.
 * SubscriptionTopicConfig is only auto-imported for R4B/R5 by HAPI starter;
 * this explicitly imports it so we can use SubscriptionTopicDispatcher,
 * R4SubscriptionTopicBuilder, and related beans in R4 mode.
 */
@Configuration
@Import(SubscriptionTopicConfig.class)
public class PasSubscriptionTopicConfiguration {
}
