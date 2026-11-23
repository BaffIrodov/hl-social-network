package ru.otus.socialnetwork.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.feed.worker-enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
public class FeedSchedulingConfiguration {}
