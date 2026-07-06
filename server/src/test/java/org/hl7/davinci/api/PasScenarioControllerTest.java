package org.hl7.davinci.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.hl7.davinci.scenarios.pas.PasScenarioService;
import org.hl7.davinci.scenarios.pas.PasScenarioService.PasScenarioDto;
import org.hl7.davinci.scenarios.pas.PasScenarioService.PasVariantDto;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ca.uhn.fhir.context.FhirContext;

class PasScenarioControllerTest {

  private PasScenarioService scenarioService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    scenarioService = mock(PasScenarioService.class);
    PasScenarioController controller = new PasScenarioController(scenarioService, FhirContext.forR4Cached());
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @Test
  void getScenarios_returnsScenarioList() throws Exception {
    when(scenarioService.getScenarios()).thenReturn(List.of(scenarioDto()));

    mockMvc.perform(get("/api/pas/scenarios"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(1)))
        .andExpect(jsonPath("$[0].id").value("oxygen"));
  }

  @Test
  void getVariants_returns404WhenScenarioMissing() throws Exception {
    when(scenarioService.findScenario("missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/pas/scenarios/missing/variants"))
        .andExpect(status().isNotFound());
  }

  @Test
  void getVariant_returnsFhirJsonBundle() throws Exception {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.COLLECTION);
    when(scenarioService.findVariantBundle("oxygen", "initial")).thenReturn(Optional.of(bundle));

    mockMvc.perform(get("/api/pas/scenarios/oxygen/variants/initial"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/fhir+json"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("\"resourceType\": \"Bundle\"")));
  }

  @Test
  void getVariant_returns404WhenVariantMissing() throws Exception {
    when(scenarioService.findVariantBundle("oxygen", "missing")).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/pas/scenarios/oxygen/variants/missing"))
        .andExpect(status().isNotFound());
  }

  private PasScenarioDto scenarioDto() {
    return new PasScenarioDto(
        "oxygen",
        "Home Oxygen",
        "Description",
        "ServiceRequest",
        "A4",
        true,
        List.of(new PasVariantDto(
            "oxygen-initial",
            "Initial",
            "$submit",
            "initial",
            "{\"resourceType\":\"Bundle\"}")));
  }
}
