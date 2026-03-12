package org.hl7.davinci.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.hl7.davinci.pas.PasRestHookInboxService;
import org.hl7.davinci.pas.PasRestHookInboxService.InboxPage;
import org.hl7.davinci.pas.PasRestHookInboxService.StoredNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

class PasRestHookInboxControllerTest {

  private PasRestHookInboxService inboxService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    inboxService = mock(PasRestHookInboxService.class);
    PasRestHookInboxController controller = new PasRestHookInboxController(
        inboxService, new ObjectMapper());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void post_validNotification_storesAndReturns200() throws Exception {
    String notificationBundle = """
        {
          "resourceType": "Bundle",
          "type": "history",
          "entry": [{
            "resource": {
              "resourceType": "Parameters",
              "parameter": [{
                "name": "subscription",
                "valueReference": { "reference": "Subscription/123" }
              }]
            }
          }, {
            "resource": {
              "resourceType": "Bundle",
              "type": "collection"
            }
          }]
        }
        """;

    mockMvc.perform(post("/api/pas/resthook-inbox")
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .content(notificationBundle))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
        .andExpect(jsonPath("$.resourceType").value("Bundle"));

    verify(inboxService).store(eq("123"), anyString());
  }

  @Test
  void post_malformedPayload_returnsFhirBundle() throws Exception {
    mockMvc.perform(post("/api/pas/resthook-inbox")
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .content("not json"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
        .andExpect(jsonPath("$.resourceType").value("Bundle"));
  }

  @Test
  void post_missingSubscriptionId_returnsFhirBundle() throws Exception {
    String bundleWithoutSubId = """
        {
          "resourceType": "Bundle",
          "entry": [{
            "resource": {
              "resourceType": "Parameters",
              "parameter": [{ "name": "type", "valueCode": "event-notification" }]
            }
          }]
        }
        """;

    mockMvc.perform(post("/api/pas/resthook-inbox")
        .contentType(MediaType.parseMediaType("application/fhir+json"))
        .content(bundleWithoutSubId))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/fhir+json"))
        .andExpect(jsonPath("$.resourceType").value("Bundle"));
  }

  @Test
  void get_returnsNotificationsWithSequence() throws Exception {
    StoredNotification notification = new StoredNotification(
        5, Instant.parse("2026-03-12T10:00:00Z"), "{\"resourceType\":\"Bundle\"}");
    InboxPage page = new InboxPage(List.of(notification), 5);
    when(inboxService.retrieve("sub-1", 3)).thenReturn(page);

    mockMvc.perform(get("/api/pas/resthook-inbox")
        .param("subscriptionId", "sub-1")
        .param("after", "3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastSequence").value(5))
        .andExpect(jsonPath("$.notifications[0].sequence").value(5))
        .andExpect(jsonPath("$.notifications[0].payload.resourceType").value("Bundle"));
  }

  @Test
  void get_emptyInbox_returnsEmptyList() throws Exception {
    when(inboxService.retrieve(anyString(), anyLong()))
        .thenReturn(new InboxPage(Collections.emptyList(), 0));

    mockMvc.perform(get("/api/pas/resthook-inbox")
        .param("subscriptionId", "sub-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notifications").isEmpty())
        .andExpect(jsonPath("$.lastSequence").value(0));
  }

  @Test
  void delete_clearsInbox() throws Exception {
    mockMvc.perform(delete("/api/pas/resthook-inbox")
        .param("subscriptionId", "sub-1"))
        .andExpect(status().isOk());

    verify(inboxService).clear("sub-1");
  }
}
