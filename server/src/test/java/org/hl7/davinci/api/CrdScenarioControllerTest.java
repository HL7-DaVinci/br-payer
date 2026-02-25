package org.hl7.davinci.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.hl7.davinci.cdshooks.CrdScenarioService;
import org.hl7.davinci.cdshooks.CrdScenarioService.CrdHookVariantDto;
import org.hl7.davinci.cdshooks.CrdScenarioService.CrdScenarioDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;

class CrdScenarioControllerTest {

  private CrdScenarioService scenarioService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    scenarioService = mock(CrdScenarioService.class);
    CrdScenarioController controller = new CrdScenarioController(scenarioService);
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void getScenarios_returnsScenarioList() throws Exception {
    when(scenarioService.getScenarios()).thenReturn(List.of(scenarioDto()));

    mockMvc.perform(get("/api/crd/scenarios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value("oxygen"));
  }

  @Test
  void getScenario_returns404WhenMissing() throws Exception {
    when(scenarioService.findScenario("missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/crd/scenarios/missing"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getHooks_supportsTrailingSlashPath() throws Exception {
    when(scenarioService.findScenario("oxygen")).thenReturn(Optional.of(scenarioDto()));

    mockMvc.perform(get("/api/crd/scenarios/oxygen/hooks/"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].hookName").value("order-sign"));
  }

  @Test
  void getHookRequest_returnsRawJsonWithApplicationJsonContentType() throws Exception {
    when(scenarioService.findHookRequestJson("oxygen", "order-sign"))
        .thenReturn(Optional.of("{\"hook\":\"order-sign\"}"));

    mockMvc.perform(get("/api/crd/scenarios/oxygen/hooks/order-sign"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/json"))
        .andExpect(content().json("{\"hook\":\"order-sign\"}"));
  }

  private CrdScenarioDto scenarioDto() {
    return new CrdScenarioDto(
        "oxygen",
        "Home Oxygen",
        "Description",
        List.of(new CrdHookVariantDto("oxygen-order-sign", "order-sign", "Order Sign", "{\"hook\":\"order-sign\"}")));
  }
}
