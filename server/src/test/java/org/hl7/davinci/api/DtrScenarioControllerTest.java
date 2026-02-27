package org.hl7.davinci.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.hl7.davinci.scenarios.dtr.DtrScenarioService;
import org.hl7.davinci.scenarios.dtr.DtrScenarioService.DtrScenarioDto;
import org.hl7.davinci.scenarios.dtr.DtrScenarioService.DtrVariantDto;
import org.hl7.fhir.r4.model.Parameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ca.uhn.fhir.context.FhirContext;

class DtrScenarioControllerTest {

  private DtrScenarioService scenarioService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    scenarioService = mock(DtrScenarioService.class);
    DtrScenarioController controller = new DtrScenarioController(scenarioService, FhirContext.forR4Cached());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void getScenarios_returnsScenarioList() throws Exception {
    when(scenarioService.getScenarios()).thenReturn(List.of(scenarioDto()));

    mockMvc.perform(get("/api/dtr/scenarios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value("oxygen"));
  }

  @Test
  void getVariants_returns404WhenScenarioMissing() throws Exception {
    when(scenarioService.findScenario("missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/dtr/scenarios/missing/variants"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getVariant_returnsRawFhirJsonWhenFound() throws Exception {
    Parameters parameters = new Parameters();
    parameters.addParameter().setName("questionnaire").setValue(new org.hl7.fhir.r4.model.StringType("q1"));
    when(scenarioService.findVariantParameters("oxygen", "canonical"))
        .thenReturn(Optional.of(parameters));

    mockMvc.perform(get("/api/dtr/scenarios/oxygen/variants/canonical"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/fhir+json"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"resourceType\": \"Parameters\"")));
  }

  @Test
  void getVariant_returns404WhenVariantMissing() throws Exception {
    when(scenarioService.findVariantParameters("oxygen", "missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/dtr/scenarios/oxygen/variants/missing"))
        .andExpect(status().isNotFound());
  }

  private DtrScenarioDto scenarioDto() {
    return new DtrScenarioDto(
        "oxygen",
        "Home Oxygen",
        "Description",
        "DeviceRequest",
        false,
        false,
        List.of(new DtrVariantDto(
            "oxygen-canonical",
            "Questionnaire",
            "canonical",
            "{\"resourceType\":\"Parameters\"}",
            Map.of())));
  }
}
