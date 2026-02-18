package org.hl7.davinci.scenarios;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdHookVariant;
import org.hl7.davinci.scenarios.CrdRequestBuilder.CrdScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrScenario;
import org.hl7.davinci.scenarios.DtrRequestBuilder.DtrVariant;
import org.hl7.davinci.scenarios.LibraryScenarioScanner.ScenarioMetadata;
import org.hl7.davinci.scenarios.PasRequestBuilder.PasScenario;
import org.hl7.davinci.scenarios.PasRequestBuilder.PasVariant;

import ca.uhn.fhir.context.FhirContext;

/**
 * Build-time generator that produces DTR, CRD, and PAS test request files from
 * library PlanDefinition and Questionnaire resources.
 *
 * <p>Usage: java TestRequestFileGenerator &lt;libraryDir&gt; &lt;outputDir&gt;
 *
 * <p>Output structure:
 * <pre>
 *   outputDir/
 *     dtr/
 *       _index.md
 *       home-oxygen-dispatch-canonical.json
 *       ...
 *     crd/
 *       _index.md
 *       order-sign/
 *         home-oxygen-therapy-order-sign.json
 *         ...
 *       order-select/
 *         ...
 *     pas/
 *       _index.md
 *       home-oxygen-therapy-initial.json
 *       ...
 * </pre>
 */
public class TestRequestFileGenerator {

  public static void main(String[] args) throws IOException {
    if (args.length < 2) {
      System.err.println("Usage: TestRequestFileGenerator <libraryDir> <outputDir>");
      System.exit(1);
    }

    Path libraryDir = Path.of(args[0]);
    Path outputDir = Path.of(args[1]);

    if (!Files.isDirectory(libraryDir)) {
      System.err.println("Library directory not found: " + libraryDir);
      System.exit(1);
    }

    FhirContext ctx = FhirContext.forR4();

    // Scan library for scenario metadata
    List<ScenarioMetadata> metadata = LibraryScenarioScanner.scan(ctx, libraryDir);
    System.out.println("Found " + metadata.size() + " scenarios from library resources");

    // Build DTR request Parameters
    List<DtrScenario> dtrScenarios = DtrRequestBuilder.build(metadata);

    // Write DTR files
    writeDtrFiles(ctx, dtrScenarios, outputDir.resolve("dtr"));

    // Build CRD request JSON
    List<CrdScenario> crdScenarios = CrdRequestBuilder.build(ctx, metadata);

    // Write CRD files
    writeCrdFiles(crdScenarios, outputDir.resolve("crd"));

    // Build PAS request Bundles
    List<PasScenario> pasScenarios = PasRequestBuilder.build(metadata);

    // Write PAS files
    writePasFiles(ctx, pasScenarios, outputDir.resolve("pas"));
  }

  private static void writeDtrFiles(FhirContext ctx, List<DtrScenario> scenarios,
      Path dtrDir) throws IOException {
    Files.createDirectories(dtrDir);

    StringBuilder index = new StringBuilder();
    index.append("# DTR Test Requests\n\n");
    index.append("Generated from library PlanDefinition and Questionnaire resources.\n");
    index.append("POST these to `Questionnaire/$questionnaire-package`.\n\n");

    int fileCount = 0;
    for (DtrScenario scenario : scenarios) {
      index.append("## ").append(scenario.name()).append("\n\n");
      index.append(scenario.description()).append("\n\n");
      index.append("| Variant | File | Type |\n");
      index.append("|---------|------|------|\n");

      for (DtrVariant variant : scenario.variants()) {
        String filename = variant.id() + ".json";
        String json = ctx.newJsonParser()
            .setPrettyPrint(true)
            .encodeResourceToString(variant.parameters());

        Files.writeString(dtrDir.resolve(filename), json);
        index.append("| ").append(variant.label())
            .append(" | [").append(filename).append("](").append(filename).append(")")
            .append(" | ").append(variant.pathType())
            .append(" |\n");
        fileCount++;
      }
      index.append("\n");
    }

    Files.writeString(dtrDir.resolve("_index.md"), index.toString());
    System.out.println("Generated " + fileCount + " DTR request files in " + dtrDir);
  }

  private static void writeCrdFiles(List<CrdScenario> scenarios,
      Path crdDir) throws IOException {
    Files.createDirectories(crdDir);

    StringBuilder index = new StringBuilder();
    index.append("# CRD Test Requests\n\n");
    index.append("Generated from library PlanDefinition and Questionnaire resources.\n");
    index.append("POST these to `/cds-services/<hook>-crd`.\n\n");

    int fileCount = 0;
    for (CrdScenario scenario : scenarios) {
      index.append("## ").append(scenario.name()).append("\n\n");
      index.append(scenario.description()).append("\n\n");
      index.append("| Hook | File |\n");
      index.append("|------|------|\n");

      for (CrdHookVariant variant : scenario.variants()) {
        Path hookDir = crdDir.resolve(variant.hookName());
        Files.createDirectories(hookDir);

        String filename = variant.id() + ".json";
        Files.writeString(hookDir.resolve(filename), variant.requestJson());

        index.append("| ").append(variant.label())
            .append(" | [").append(filename).append("](")
            .append(variant.hookName()).append("/").append(filename).append(")")
            .append(" |\n");
        fileCount++;
      }
      index.append("\n");
    }

    Files.writeString(crdDir.resolve("_index.md"), index.toString());
    System.out.println("Generated " + fileCount + " CRD request files in " + crdDir);
  }

  private static void writePasFiles(FhirContext ctx, List<PasScenario> scenarios,
      Path pasDir) throws IOException {
    Files.createDirectories(pasDir);

    StringBuilder index = new StringBuilder();
    index.append("# PAS Test Requests\n\n");
    index.append("Generated from library PlanDefinition resources.\n");
    index.append("POST `$submit` variants to `Claim/$submit`; `$inquire` variants to `Claim/$inquire`.\n\n");

    int fileCount = 0;
    for (PasScenario scenario : scenarios) {
      index.append("## ").append(scenario.name()).append("\n\n");
      index.append(scenario.description()).append("\n\n");
      index.append("| Variant | Operation | Payload Type | File |\n");
      index.append("|---------|-----------|--------------|------|\n");

      for (PasVariant variant : scenario.variants()) {
        String filename = variant.id() + ".json";
        String json = ctx.newJsonParser()
            .setPrettyPrint(true)
            .encodeResourceToString(variant.bundle());

        Files.writeString(pasDir.resolve(filename), json);
        index.append("| ").append(variant.label())
            .append(" | ").append(variant.operation())
            .append(" | ").append(variant.payloadType())
            .append(" | [").append(filename).append("](").append(filename).append(")")
            .append(" |\n");
        fileCount++;
      }
      index.append("\n");
    }

    Files.writeString(pasDir.resolve("_index.md"), index.toString());
    System.out.println("Generated " + fileCount + " PAS request files in " + pasDir);
  }
}
