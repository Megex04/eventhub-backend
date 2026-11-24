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
import jakarta.persistence.OptimisticLockException;
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
        EventEntityMapper.copyState(event, stored);
      }
      entityManager.flush();
    } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
      throw new EventVersionConflictException(event.id());
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

  private static void requireCurrent(EventEntity stored, Event event) {
    if (stored.getVersion() != event.version()) {
      throw new EventVersionConflictException(event.id());
    }
  }
}
