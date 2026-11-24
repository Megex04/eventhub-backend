package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.application.query.Viewer;
import com.eventhub.catalog.domain.EventStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the listing query. Visibility and every filter are part of the SQL; the query text is
 * assembled only from fixed fragments and all caller-supplied values are bound parameters.
 */
final class EventSearchQuery {

  /**
   * Escape character of LIKE patterns. Not a backslash, so a backslash typed by a user is literal.
   */
  private static final char LIKE_ESCAPE = '!';

  private static final String SELECT =
      "select new "
          + EventSummary.class.getName()
          + "(e.id, e.title, e.venue, e.startsAt, e.endsAt, e.status) from EventEntity e where ";

  private final List<String> predicates = new ArrayList<>();
  private final Map<String, Object> parameters = new LinkedHashMap<>();
  private final boolean unsatisfiable;

  private EventSearchQuery(EventSearchCriteria criteria) {
    parameters.put("published", EventStatus.PUBLISHED);
    if (criteria.viewer() instanceof Viewer.Authenticated user) {
      predicates.add("(e.status = :published or e.organizerId = :viewer)");
      parameters.put("viewer", user.userId());
      if (criteria.onlyMine()) {
        predicates.add("e.organizerId = :viewer");
      }
      unsatisfiable = false;
    } else {
      predicates.add("e.status = :published");
      unsatisfiable = criteria.onlyMine();
    }
    criteria
        .status()
        .ifPresent(status -> {
          predicates.add("e.status = :status");
          parameters.put("status", status);
        });
    criteria
        .from()
        .ifPresent(from -> {
          predicates.add("e.startsAt >= :from");
          parameters.put("from", from);
        });
    criteria
        .to()
        .ifPresent(to -> {
          predicates.add("e.startsAt <= :to");
          parameters.put("to", to);
        });
    criteria
        .text()
        .ifPresent(text -> {
          predicates.add(
              "(e.title ilike :text escape '"
                  + LIKE_ESCAPE
                  + "' or e.venue ilike :text escape '"
                  + LIKE_ESCAPE
                  + "')");
          parameters.put("text", "%" + escapeLike(text) + "%");
        });
  }

  static PageResult<EventSummary> run(
      EntityManager em, EventSearchCriteria criteria, EventSort sort, PageRequest page) {
    return new EventSearchQuery(criteria).execute(em, sort, page);
  }

  /** Makes {@code %}, {@code _} and the escape character itself match literally. */
  static String escapeLike(String text) {
    String escape = String.valueOf(LIKE_ESCAPE);
    return text.replace(escape, escape + escape)
        .replace("%", escape + "%")
        .replace("_", escape + "_");
  }

  /** Whitelist of sortable columns: the clause never contains caller-supplied text. */
  static String orderBy(EventSort sort) {
    return switch (sort) {
      case STARTS_AT_ASC -> "e.startsAt asc, e.id asc";
      case STARTS_AT_DESC -> "e.startsAt desc, e.id asc";
      case TITLE_ASC -> "lower(e.title) asc, e.id asc";
      case TITLE_DESC -> "lower(e.title) desc, e.id asc";
    };
  }

  private PageResult<EventSummary> execute(EntityManager em, EventSort sort, PageRequest page) {
    if (unsatisfiable) {
      return new PageResult<>(List.of(), page.page(), page.size(), 0);
    }
    String where = String.join(" and ", predicates);
    long total = count(em, where);
    long offset = (long) page.page() * page.size();
    if (offset >= total) {
      return new PageResult<>(List.of(), page.page(), page.size(), total);
    }
    List<EventSummary> content =
        bind(em.createQuery(SELECT + where + " order by " + orderBy(sort), EventSummary.class))
            .setFirstResult((int) offset)
            .setMaxResults(page.size())
            .getResultList();
    return new PageResult<>(content, page.page(), page.size(), total);
  }

  private long count(EntityManager em, String where) {
    Query query = em.createQuery("select count(e) from EventEntity e where " + where, Long.class);
    return (Long) bind(query).getSingleResult();
  }

  private <T extends Query> T bind(T query) {
    parameters.forEach(query::setParameter);
    return query;
  }
}
