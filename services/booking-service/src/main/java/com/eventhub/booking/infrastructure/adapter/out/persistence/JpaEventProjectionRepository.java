package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.EventProjectionRepository;
import com.eventhub.booking.domain.EventProjection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the projection and its zones. The entity read by {@code findById} stays managed for the
 * rest of the transaction, so a concurrent commit between that read and this save is detected by
 * the version and surfaces as {@link ConcurrencyConflictException} (the message is redelivered).
 */
@Repository
@Transactional
public class JpaEventProjectionRepository implements EventProjectionRepository {

  private final EntityManager entityManager;

  public JpaEventProjectionRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Optional<EventProjection> findById(UUID eventId) {
    return entityManager
        .createQuery(
            "select p from EventProjectionEntity p left join fetch p.zones where p.eventId = :id",
            EventProjectionEntity.class)
        .setParameter("id", eventId)
        .getResultStream()
        .findFirst()
        .map(PersistenceMapper::toDomain);
  }

  @Override
  public void save(EventProjection projection) {
    try {
      EventProjectionEntity stored =
          entityManager.find(EventProjectionEntity.class, projection.eventId());
      if (stored == null) {
        stored = new EventProjectionEntity(projection.eventId());
        PersistenceMapper.applyState(projection, stored);
        entityManager.persist(stored);
      } else {
        PersistenceMapper.applyState(projection, stored);
      }
      entityManager.flush();
    } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
      throw new ConcurrencyConflictException(
          "Projection of event %s was modified concurrently".formatted(projection.eventId()), e);
    }
  }
}
