package com.eventhub.booking.application.service;

import com.eventhub.booking.domain.RequestedItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SHA-256 of the normalized creation request: the event and the items sorted by zone, so the same
 * request written in another order is still the same request. It tells a safe retry of an
 * Idempotency-Key from a reuse of the key for something else.
 */
final class RequestFingerprint {

  private RequestFingerprint() {}

  static String of(UUID eventId, List<RequestedItem> items) {
    String normalized =
        eventId
            + "|"
            + items.stream()
                .sorted(
                    Comparator.comparing(RequestedItem::zoneId)
                        .thenComparing(RequestedItem::quantity))
                .map(item -> item.zoneId() + ":" + item.quantity())
                .collect(Collectors.joining(","));
    return HexFormat.of().formatHex(sha256(normalized));
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
