package com.eventhub.catalog.infrastructure.config;

import com.eventhub.catalog.infrastructure.adapter.out.messaging.OutboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Switches on what the outbox relay needs: its settings and the scheduler that drives it. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class MessagingConfig {}
