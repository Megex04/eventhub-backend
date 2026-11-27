package com.eventhub.booking.support;

import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Stops the {@code @Scheduled} jobs of a cached context. A test class that turns a job on leaves
 * its context in Spring's cache for the rest of the run; without this the job would keep polling
 * the shared database, with a clock frozen wherever that test left it.
 */
public final class ScheduledJobs {

  private ScheduledJobs() {}

  public static void cancelAll(ApplicationContext context) {
    context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
        .flatMap(holder -> holder.getScheduledTasks().stream())
        .forEach(ScheduledTask::cancel);
  }
}
