package com.eventhub.catalog.infrastructure.adapter.in.web;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport.Level;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Every response the service gives is checked against {@code contracts/openapi/catalog-api.yaml}:
 * the status must be declared for that operation, and content type, headers and body must match the
 * declared schema. A status the code returns but the contract does not declare fails here.
 *
 * <p>Requests are deliberately malformed in the error cases, so the validator is told to judge
 * responses only: request body, parameter and security findings are ignored, while an unknown path
 * or method is still an error.
 */
class OpenApiConformanceIT extends WebIntegrationTest {

  // Read from the contract itself, never copied into the module.
  private static final String SPEC =
      Path.of("../../contracts/openapi/catalog-api.yaml")
          .toAbsolutePath()
          .normalize()
          .toUri()
          .toString();

  private static final OpenApiInteractionValidator VALIDATOR =
      OpenApiInteractionValidator.createFor(SPEC)
          .withLevelResolver(
              LevelResolver.create()
                  .withLevel("validation.request.body", Level.IGNORE)
                  .withLevel("validation.request.parameter", Level.IGNORE)
                  .withLevel("validation.request.security", Level.IGNORE)
                  .build())
          .build();

  private static final String NO_SUCH = "00000000-0000-4000-8000-000000000000";

  private void expect(MockHttpServletRequestBuilder request, int status) throws Exception {
    mockMvc.perform(request).andExpect(status().is(status)).andExpect(openApi().isValid(VALIDATOR));
  }

  private static MockHttpServletRequestBuilder as(
      String token, MockHttpServletRequestBuilder request) {
    return withToken(request, token);
  }

  private static String event(UUID id) {
    return EVENTS + "/" + id;
  }

  private static String badBody() {
    return "{\"title\":\"ab\"}";
  }

  // ---- events ----

  @Test
  void should_conform_when_creating_events() throws Exception {
    String twoZonesSameName =
        """
        {"title":"Rock Fest","venue":"Estadio","startsAt":"%s","endsAt":"%s","zones":[
          {"name":"VIP","price":{"amount":"10.00","currency":"PEN"},"capacity":10},
          {"name":"vip","price":{"amount":"10.00","currency":"PEN"},"capacity":10}]}
        """
            .formatted(inDays(30), inDays(31));

    expect(json(as(organizer(), post(EVENTS)), eventJson("Rock Fest")), 201);
    expect(json(as(organizer(), post(EVENTS)), badBody()), 400);
    expect(json(post(EVENTS), eventJson("Rock Fest")), 401);
    expect(json(as(attendee(), post(EVENTS)), eventJson("Rock Fest")), 403);
    expect(json(as(organizer(), post(EVENTS)), twoZonesSameName), 422);
  }

  @Test
  void should_conform_when_listing_events() throws Exception {
    createPublished(organizer());

    expect(get(EVENTS), 200);
    expect(as(organizer(), get(EVENTS).param("mine", "true").param("status", "PUBLISHED")), 200);
    expect(get(EVENTS).param("size", "0"), 400);
    expect(get(EVENTS).param("mine", "true"), 401);
  }

  @Test
  void should_conform_when_getting_an_event() throws Exception {
    UUID published = createPublished(organizer());

    expect(get(event(published)), 200);
    expect(get(EVENTS + "/not-a-uuid"), 400);
    expect(as("garbage", get(event(published))), 401);
    expect(get(EVENTS + "/" + NO_SUCH), 404);
  }

  @Test
  void should_conform_when_updating_an_event() throws Exception {
    UUID draft = createDraft(organizer());
    UUID published = createPublished(organizer());

    expect(json(as(organizer(), put(event(draft))), eventJson("Renamed Fest")), 200);
    expect(json(as(organizer(), put(event(draft))), badBody()), 400);
    expect(json(put(event(draft)), eventJson("Renamed Fest")), 401);
    expect(json(as(otherOrganizer(), put(event(published))), eventJson("Hijacked")), 403);
    expect(json(as(organizer(), put(EVENTS + "/" + NO_SUCH)), eventJson("Renamed Fest")), 404);
    expect(json(as(organizer(), put(event(published))), eventJson("Renamed Fest")), 409);
  }

  @Test
  void should_conform_when_deleting_an_event() throws Exception {
    UUID draft = createDraft(organizer());
    UUID published = createPublished(organizer());

    expect(as(organizer(), delete(event(draft))), 204);
    expect(as(organizer(), delete(EVENTS + "/not-a-uuid")), 400);
    expect(delete(event(published)), 401);
    expect(as(otherOrganizer(), delete(event(published))), 403);
    expect(as(organizer(), delete(EVENTS + "/" + NO_SUCH)), 404);
    expect(as(organizer(), delete(event(published))), 409);
  }

  // ---- zones ----

  @Test
  void should_conform_when_adding_a_zone() throws Exception {
    UUID draft = createDraftWithZone(organizer());
    UUID published = createPublished(organizer());
    String path = event(draft) + "/zones";

    expect(json(as(organizer(), post(path)), zoneJson("VIP", "150.00")), 201);
    expect(json(as(organizer(), post(path)), "{\"name\":\"\"}"), 400);
    expect(json(post(path), zoneJson("Other", "10.00")), 401);
    expect(json(as(attendee(), post(path)), zoneJson("Other", "10.00")), 403);
    expect(
        json(as(organizer(), post(EVENTS + "/" + NO_SUCH + "/zones")), zoneJson("A", "1.00")), 404);
    expect(json(as(organizer(), post(event(published) + "/zones")), zoneJson("A", "1.00")), 409);
    expect(json(as(organizer(), post(path)), zoneJson("vip", "10.00")), 422);
  }

  @Test
  void should_conform_when_updating_a_zone() throws Exception {
    UUID draft = createDraft(organizer());
    UUID zone = addZone(organizer(), draft, "General", "100.00");
    addZone(organizer(), draft, "VIP", "150.00");
    UUID published = createDraft(organizer());
    UUID publishedZone = addZone(organizer(), published, "General", "100.00");
    expect(as(organizer(), post(event(published) + "/publish")), 200);
    String zonePath = event(draft) + "/zones/" + zone;

    expect(json(as(organizer(), put(zonePath)), zoneJson("Platea", "75.50")), 200);
    expect(json(as(organizer(), put(zonePath)), "{\"name\":\"\"}"), 400);
    expect(json(put(zonePath), zoneJson("Platea", "75.50")), 401);
    expect(json(as(attendee(), put(zonePath)), zoneJson("A", "1.00")), 403);
    expect(
        json(as(organizer(), put(event(draft) + "/zones/" + NO_SUCH)), zoneJson("A", "1.00")), 404);
    expect(
        json(
            as(organizer(), put(event(published) + "/zones/" + publishedZone)),
            zoneJson("A", "1.00")),
        409);
    expect(json(as(organizer(), put(zonePath)), zoneJson("vip", "10.00")), 422);
  }

  @Test
  void should_conform_when_removing_a_zone() throws Exception {
    UUID draft = createDraft(organizer());
    UUID zone = addZone(organizer(), draft, "General", "100.00");
    UUID published = createDraft(organizer());
    UUID publishedZone = addZone(organizer(), published, "General", "100.00");
    expect(as(organizer(), post(event(published) + "/publish")), 200);
    String zonePath = event(draft) + "/zones/" + zone;

    expect(as(organizer(), delete(zonePath)), 204);
    expect(as(organizer(), delete(event(draft) + "/zones/not-a-uuid")), 400);
    expect(delete(zonePath), 401);
    expect(as(attendee(), delete(zonePath)), 403);
    expect(as(organizer(), delete(event(draft) + "/zones/" + NO_SUCH)), 404);
    expect(as(organizer(), delete(event(published) + "/zones/" + publishedZone)), 409);
  }

  // ---- lifecycle ----

  @Test
  void should_conform_when_publishing() throws Exception {
    UUID ready = createDraftWithZone(organizer());
    UUID withoutZones = createDraft(organizer());

    expect(as(organizer(), post(event(ready) + "/publish")), 200);
    expect(as(organizer(), post(EVENTS + "/not-a-uuid/publish")), 400);
    expect(post(event(ready) + "/publish"), 401);
    expect(as(attendee(), post(event(ready) + "/publish")), 403);
    expect(as(organizer(), post(EVENTS + "/" + NO_SUCH + "/publish")), 404);
    expect(as(organizer(), post(event(ready) + "/publish")), 409);
    expect(as(organizer(), post(event(withoutZones) + "/publish")), 422);
  }

  @Test
  void should_conform_when_cancelling() throws Exception {
    UUID published = createPublished(organizer());
    UUID draft = createDraftWithZone(organizer());
    String reason = "{\"reason\":\"Venue unavailable\"}";

    expect(json(as(organizer(), post(event(published) + "/cancel")), reason), 200);
    expect(json(as(organizer(), post(event(published) + "/cancel")), "{\"reason\":\"no\"}"), 400);
    expect(json(post(event(published) + "/cancel"), reason), 401);
    expect(json(as(attendee(), post(event(published) + "/cancel")), reason), 403);
    expect(json(as(organizer(), post(EVENTS + "/" + NO_SUCH + "/cancel")), reason), 404);
    expect(json(as(organizer(), post(event(draft) + "/cancel")), reason), 409);
  }

  @Test
  void should_conform_when_rescheduling() throws Exception {
    UUID published = createPublished(organizer());
    UUID draft = createDraftWithZone(organizer());
    Instant start = inDays(45);
    String valid =
        "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(start, start.plus(Duration.ofHours(3)));
    Instant past = Instant.now().minus(Duration.ofDays(1));
    String inThePast =
        "{\"startsAt\":\"%s\",\"endsAt\":\"%s\"}".formatted(past, past.plus(Duration.ofHours(3)));
    String path = event(published) + "/reschedule";

    expect(json(as(organizer(), post(path)), valid), 200);
    expect(json(as(organizer(), post(path)), "{}"), 400);
    expect(json(post(path), valid), 401);
    expect(json(as(attendee(), post(path)), valid), 403);
    expect(json(as(organizer(), post(EVENTS + "/" + NO_SUCH + "/reschedule")), valid), 404);
    expect(json(as(organizer(), post(event(draft) + "/reschedule")), valid), 409);
    expect(json(as(organizer(), post(path)), inThePast), 422);
  }
}
