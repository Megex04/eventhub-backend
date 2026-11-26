package com.eventhub.booking.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * An integration test against the real application context and a real PostgreSQL (one container per
 * JVM, Flyway-migrated, Hibernate in {@code validate} mode), in the {@code test} profile, with
 * empty tables, a clock the test controls and a payment gateway the test drives. An annotation
 * rather than a base class so that the abstract repository contracts, which have their own
 * superclass, can use it too.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(
    initializers = {
      SharedPostgres.class,
      SharedRabbit.class,
      TestOutboxSettings.class,
      TestConsumerSettings.class
    })
@Import(TestSupportConfig.class)
@ExtendWith(CleanDatabaseExtension.class)
public @interface PersistenceIT {}
