package com.eventhub.catalog.infrastructure.adapter.out.persistence;

import com.eventhub.catalog.application.exception.EventVersionConflictException;
import com.eventhub.catalog.application.port.out.EventRepository;
import com.eventhub.catalog.application.query.EventSearchCriteria;
import com.eventhub.catalog.application.query.EventSort;
import com.eventhub.catalog.application.query.EventSummary;
import com.eventhub.catalog.application.query.PageRequest;
import com.eventhub.catalog.application.query.PageResult;
import com.eventhub.catalog.domain.Event;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA implementation of the persistence port.
 *
 * <p>Stale writes are detected twice: the version carried by the aggregate is compared with the
 * stored one up front, and Hibernate's {@code UPDATE ... WHERE version = ?} catches a concurrent
 * commit that lands after the aggregate was loaded. Both surface as {@link
 * EventVersionConflictException}; {@code flush} runs inside the method so the conflict is raised
 * here, before the caller writes anything else (such as the outbox row) in the same transaction.
 *
 * <p>A saved aggregate keeps the version it was loaded with; load it again before saving twice.
 */
@Repository
@Transactional
public class JpaEventRepository implements EventRepository {

  private static final String ZONE_NAME_CONSTRAINT = "uq_zone_event_name";
  private static final String UNIQUE_VIOLATION = "23505";

  private final EntityManager entityManager;

  public JpaEventRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Event> findById(UUID id) {
    return entityManager
        .createQuery(
            "select e from EventEntity e left join fetch e.zones where e.id = :id",
            EventEntity.class)
        .setParameter("id", id)
        .getResultStream()
        .findFirst()
        .map(EventEntityMapper::toDomain);
  }

  @Override
  public void save(Event event) {
    EventEntity stored = entityManager.find(EventEntity.class, event.id());
    try {
      if (stored == null) {
        entityManager.persist(EventEntityMapper.toNewEntity(event));
      } else {
        requireCurrent(stored, event);
        claimRow(stored);
        EventEntityMapper.copyState(event, stored);
      }
      entityManager.flush();
      failFastOnDuplicateZoneName();
    } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
      throw new EventVersionConflictException(event.id());
    } catch (PersistenceException e) {
      if (isDuplicateZoneName(e)) {
        throw new EventVersionConflictException(event.id());
      }
      throw e;
    }
  }

  @Override
  public void delete(Event event) {
    EventEntity stored = entityManager.find(EventEntity.class, event.id());
    if (stored == null) {
      return;
    }
    requireCurrent(stored, event);
    try {
      entityManager.remove(stored);
      entityManager.flush();
    } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
      throw new EventVersionConflictException(event.id());
    }
  }

  @Override
  @Transactional(readOnly = true)
  public PageResult<EventSummary> search(
      EventSearchCriteria criteria, EventSort sort, PageRequest page) {
    return EventSearchQuery.run(entityManager, criteria, sort, page);
  }

  /**
   * Makes this transaction the only writer of the event row, and bumps its version, before any
   * child row is touched. Two writers would otherwise insert their zones first and then wait for
   * each other (the deferred unique check at commit against the row update): a deadlock. It also
   * guarantees a new version for every save, even when only the zones changed and the row itself is
   * not dirty. A writer that started from an older version fails here.
   */
  private void claimRow(EventEntity stored) {
    entityManager.lock(stored, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
  }

  /**
   * The zone-name constraint is deferred so one save can swap names, which moves its check to the
   * commit, outside this class. Checking it now, once everything is flushed, surfaces a duplicate
   * committed by someone else as a conflict here instead of as a failed commit.
   */
  private void failFastOnDuplicateZoneName() {
    entityManager
        .createNativeQuery("SET CONSTRAINTS " + ZONE_NAME_CONSTRAINT + " IMMEDIATE")
        .executeUpdate();
    entityManager
        .createNativeQuery("SET CONSTRAINTS " + ZONE_NAME_CONSTRAINT + " DEFERRED")
        .executeUpdate();
  }

  private static boolean isDuplicateZoneName(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql
          && UNIQUE_VIOLATION.equals(sql.getSQLState())
          && String.valueOf(sql.getMessage()).contains(ZONE_NAME_CONSTRAINT)) {
        return true;
      }
    }
    return false;
  }

  private static void requireCurrent(EventEntity stored, Event event) {
    if (stored.getVersion() != event.version()) {
      throw new EventVersionConflictException(event.id());
    }
  }
}
