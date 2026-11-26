package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.Page;
import com.eventhub.booking.application.PageQuery;
import com.eventhub.booking.application.port.out.BookingRepository;
import com.eventhub.booking.application.port.out.ConcurrencyConflictException;
import com.eventhub.booking.application.port.out.DuplicateIdempotencyKeyException;
import com.eventhub.booking.application.port.out.IdempotencyRecord;
import com.eventhub.booking.application.port.out.StoredBooking;
import com.eventhub.booking.domain.Booking;
import com.eventhub.booking.domain.BookingStatus;
import com.eventhub.booking.domain.Payment;
import com.eventhub.booking.domain.PaymentInProgressException;
import com.eventhub.booking.domain.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JPA implementation of the booking port, with the discipline of ADR 0004: a save of an existing
 * booking claims the row first ({@code PESSIMISTIC_FORCE_INCREMENT}), so the version rises by
 * exactly one whether or not a column changed and a writer that read an older version fails right
 * there with {@link ConcurrencyConflictException}; {@code flush} runs inside the method so every
 * conflict and constraint is raised here and not at commit.
 *
 * <p>The saved aggregate is returned, with its new version: use that one from then on.
 */
@Repository
@Transactional
public class JpaBookingRepository implements BookingRepository {

  private static final String IDEMPOTENCY_CONSTRAINT = "uq_booking_user_idempotency";
  private static final String ACTIVE_PAYMENT_INDEX = "uq_payment_active_per_booking";
  private static final Set<PaymentStatus> ACTIVE =
      EnumSet.of(PaymentStatus.INITIATED, PaymentStatus.CAPTURED, PaymentStatus.REFUND_PENDING);

  private final EntityManager entityManager;
  private final Clock clock;

  public JpaBookingRepository(EntityManager entityManager, Clock clock) {
    this.entityManager = entityManager;
    this.clock = clock;
  }

  @Override
  public Booking saveNew(Booking booking, IdempotencyRecord idempotency) {
    try {
      BookingEntity entity = PersistenceMapper.toNewEntity(booking, idempotency);
      entityManager.persist(entity);
      entityManager.flush();
      return toDomain(entity);
    } catch (PersistenceException e) {
      if (Conflicts.isUniqueViolation(e, IDEMPOTENCY_CONSTRAINT)) {
        throw new DuplicateIdempotencyKeyException(idempotency.userId(), idempotency.key());
      }
      throw e;
    }
  }

  @Override
  public Booking save(Booking booking) {
    BookingEntity stored = entityManager.find(BookingEntity.class, booking.id());
    if (stored == null) {
      throw new IllegalStateException("Booking " + booking.id() + " does not exist");
    }
    try {
      requireCurrent(stored, booking);
      entityManager.lock(stored, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
      PersistenceMapper.applyState(booking, stored);
      syncPayment(booking);
      entityManager.flush();
    } catch (OptimisticLockException | ObjectOptimisticLockingFailureException e) {
      throw new ConcurrencyConflictException(
          "Booking %s was modified concurrently".formatted(booking.id()), e);
    } catch (PersistenceException e) {
      if (Conflicts.isUniqueViolation(e, ACTIVE_PAYMENT_INDEX)) {
        throw new PaymentInProgressException(
            booking.payment().map(Payment::id).orElse(booking.id()));
      }
      throw e;
    }
    return toDomain(stored);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Booking> findById(UUID id) {
    return entityManager
        .createQuery(
            "select b from BookingEntity b left join fetch b.items where b.id = :id",
            BookingEntity.class)
        .setParameter("id", id)
        .getResultStream()
        .findFirst()
        .map(this::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<StoredBooking> findByIdempotencyKey(UUID userId, UUID key) {
    return entityManager
        .createQuery(
            "select b from BookingEntity b left join fetch b.items"
                + " where b.userId = :userId and b.idempotencyKey = :key",
            BookingEntity.class)
        .setParameter("userId", userId)
        .setParameter("key", key)
        .getResultStream()
        .findFirst()
        .map(entity -> new StoredBooking(toDomain(entity), entity.getRequestFingerprint()));
  }

  /**
   * Two queries for the page (its ids, then the bookings of those ids), one more for all payments.
   */
  @Override
  @Transactional(readOnly = true)
  public Page<Booking> findByUser(UUID userId, Optional<BookingStatus> status, PageQuery page) {
    String filter =
        " where b.userId = :userId" + (status.isPresent() ? " and b.status = :status" : "");
    var count =
        entityManager.createQuery("select count(b) from BookingEntity b" + filter, Long.class);
    var ids =
        entityManager.createQuery(
            "select b.id from BookingEntity b"
                + filter
                + " order by b.createdAt "
                + (page.newestFirst() ? "desc, b.id desc" : "asc, b.id asc"),
            UUID.class);
    count.setParameter("userId", userId);
    ids.setParameter("userId", userId);
    status.ifPresent(s -> {
      count.setParameter("status", s);
      ids.setParameter("status", s);
    });
    ids.setFirstResult(page.page() * page.size()).setMaxResults(page.size());
    return new Page<>(
        loadInOrder(ids.getResultList()), page.page(), page.size(), count.getSingleResult());
  }

  /**
   * Locks the rows it returns ({@code FOR UPDATE SKIP LOCKED}): a second expirer skips them, and a
   * payment that wants to save one of them waits for this transaction instead of racing it.
   */
  @Override
  @SuppressWarnings("unchecked")
  public List<Booking> findExpirable(Instant now, int limit) {
    List<UUID> ids =
        entityManager
            .createNativeQuery(
                "select id from booking where status = 'PENDING' and expires_at <= :now"
                    + " order by expires_at, id limit :limit for update skip locked",
                UUID.class)
            .setParameter("now", now)
            .setParameter("limit", limit)
            .getResultList();
    return loadInOrder(ids);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findActiveIdsByEvent(UUID eventId, int limit) {
    return entityManager
        .createQuery(
            "select b.id from BookingEntity b where b.eventId = :eventId"
                + " and b.status in :statuses order by b.createdAt, b.id",
            UUID.class)
        .setParameter("eventId", eventId)
        .setParameter("statuses", List.of(BookingStatus.PENDING, BookingStatus.CONFIRMED))
        .setMaxResults(limit)
        .getResultList();
  }

  private void syncPayment(Booking booking) {
    booking
        .payment()
        .ifPresent(payment -> {
          PaymentEntity stored = entityManager.find(PaymentEntity.class, payment.id());
          if (stored == null) {
            entityManager.persist(
                PersistenceMapper.toNewEntity(booking.id(), payment, clock.instant()));
          } else {
            PersistenceMapper.applyState(payment, stored);
          }
        });
  }

  private List<Booking> loadInOrder(List<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    Map<UUID, BookingEntity> byId = new HashMap<>();
    entityManager
        .createQuery(
            "select distinct b from BookingEntity b left join fetch b.items where b.id in :ids",
            BookingEntity.class)
        .setParameter("ids", ids)
        .getResultList()
        .forEach(entity -> byId.put(entity.getId(), entity));
    Map<UUID, Payment> payments = currentPayments(ids);
    return ids.stream()
        .map(byId::get)
        .map(entity ->
            PersistenceMapper.toDomain(entity, Optional.ofNullable(payments.get(entity.getId()))))
        .toList();
  }

  private Booking toDomain(BookingEntity entity) {
    return PersistenceMapper.toDomain(
        entity, Optional.ofNullable(currentPayments(List.of(entity.getId())).get(entity.getId())));
  }

  /** The live attempt of each booking, or the latest one when none is live. One query for all. */
  private Map<UUID, Payment> currentPayments(List<UUID> bookingIds) {
    Map<UUID, PaymentEntity> chosen = new HashMap<>();
    entityManager
        .createQuery(
            "select p from PaymentEntity p where p.bookingId in :ids order by p.createdAt desc, p.id desc",
            PaymentEntity.class)
        .setParameter("ids", bookingIds)
        .getResultList()
        .forEach(payment ->
            chosen.merge(
                payment.getBookingId(),
                payment,
                (current, candidate) ->
                    !ACTIVE.contains(current.getStatus()) && ACTIVE.contains(candidate.getStatus())
                        ? candidate
                        : current));
    Map<UUID, Payment> result = new HashMap<>();
    chosen.forEach(
        (bookingId, entity) -> result.put(bookingId, PersistenceMapper.toDomain(entity)));
    return result;
  }

  private static void requireCurrent(BookingEntity stored, Booking booking) {
    if (stored.getVersion() != booking.version()) {
      throw new ConcurrencyConflictException(
          "Booking %s was modified concurrently".formatted(booking.id()),
          new OptimisticLockException("stored version " + stored.getVersion()));
    }
  }
}
