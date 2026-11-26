package com.eventhub.booking.infrastructure.adapter.out.persistence;

import com.eventhub.booking.application.port.out.InventoryRepository;
import com.eventhub.booking.domain.InsufficientInventoryException;
import com.eventhub.booking.domain.InventoryInvariantException;
import com.eventhub.booking.domain.RequestedItem;
import com.eventhub.booking.domain.ZoneInventory;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory with plain SQL, on purpose: reserving is one conditional {@code UPDATE} per zone, so
 * the database decides atomically whether the tickets fit, and no {@code ZoneInventory} is loaded
 * and written back (which would lose updates between concurrent buyers). Zones are always updated
 * in zoneId order, so two bookings that share zones take their row locks in the same order and
 * cannot deadlock. The class is transactional: a request that fails halfway leaves nothing held.
 */
@Repository
@Transactional
public class JdbcInventoryRepository implements InventoryRepository {

  private final JdbcClient jdbc;

  public JdbcInventoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void reserve(UUID eventId, List<RequestedItem> items) {
    for (RequestedItem item : inZoneOrder(items)) {
      int updated =
          jdbc.sql(
                  "UPDATE zone_inventory SET reserved = reserved + :quantity, version = version + 1"
                      + " WHERE event_id = :eventId AND zone_id = :zoneId"
                      + " AND reserved + :quantity <= capacity")
              .param("quantity", item.quantity())
              .param("eventId", eventId)
              .param("zoneId", item.zoneId())
              .update();
      if (updated == 0) {
        throw new InsufficientInventoryException(
            item.zoneId(), item.quantity(), available(eventId, item.zoneId()));
      }
    }
  }

  @Override
  public void release(UUID eventId, List<RequestedItem> items) {
    for (RequestedItem item : inZoneOrder(items)) {
      int updated =
          jdbc.sql(
                  "UPDATE zone_inventory SET reserved = reserved - :quantity, version = version + 1"
                      + " WHERE event_id = :eventId AND zone_id = :zoneId"
                      + " AND reserved - :quantity >= 0")
              .param("quantity", item.quantity())
              .param("eventId", eventId)
              .param("zoneId", item.zoneId())
              .update();
      if (updated == 0) {
        throw new InventoryInvariantException(
            item.zoneId(), reserved(eventId, item.zoneId()), item.quantity());
      }
    }
  }

  /** An upsert that never touches {@code reserved}: a replay of the publication keeps the sales. */
  @Override
  public void upsertCapacity(UUID eventId, UUID zoneId, int capacity) {
    jdbc.sql(
            "INSERT INTO zone_inventory (event_id, zone_id, capacity, reserved, version)"
                + " VALUES (:eventId, :zoneId, :capacity, 0, 0)"
                + " ON CONFLICT (event_id, zone_id) DO UPDATE"
                + " SET capacity = EXCLUDED.capacity, version = zone_inventory.version + 1"
                + " WHERE zone_inventory.capacity <> EXCLUDED.capacity")
        .param("eventId", eventId)
        .param("zoneId", zoneId)
        .param("capacity", capacity)
        .update();
  }

  @Override
  @Transactional(readOnly = true)
  public List<ZoneInventory> findByEvent(UUID eventId) {
    return jdbc.sql(
            "SELECT event_id, zone_id, capacity, reserved, version FROM zone_inventory"
                + " WHERE event_id = :eventId ORDER BY zone_id")
        .param("eventId", eventId)
        .query((rs, row) ->
            ZoneInventory.restore(
                rs.getObject("event_id", UUID.class),
                rs.getObject("zone_id", UUID.class),
                rs.getInt("capacity"),
                rs.getInt("reserved"),
                rs.getLong("version")))
        .list();
  }

  private int available(UUID eventId, UUID zoneId) {
    return jdbc.sql(
            "SELECT capacity - reserved FROM zone_inventory"
                + " WHERE event_id = :eventId AND zone_id = :zoneId")
        .param("eventId", eventId)
        .param("zoneId", zoneId)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  private int reserved(UUID eventId, UUID zoneId) {
    return jdbc.sql(
            "SELECT reserved FROM zone_inventory WHERE event_id = :eventId AND zone_id = :zoneId")
        .param("eventId", eventId)
        .param("zoneId", zoneId)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  private static List<RequestedItem> inZoneOrder(List<RequestedItem> items) {
    return items.stream().sorted(Comparator.comparing(RequestedItem::zoneId)).toList();
  }
}
