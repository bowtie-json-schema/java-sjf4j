
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.sjf4j.CompiledInstances;
import org.sjf4j.annotation.binding.CompiledBinder;
import org.sjf4j.annotation.binding.Backend;
import org.sjf4j.annotation.binding.Read;
import org.sjf4j.annotation.binding.Write;
import org.sjf4j.RuntimeContext;
import org.sjf4j.Sjf4j;
import org.sjf4j.annotation.node.NodeProperty;
import org.sjf4j.schema.JsonSchema;
import org.sjf4j.schema.SchemaDialect;
import org.sjf4j.schema.SchemaPlan;
import org.sjf4j.schema.SchemaRegistry;
import org.sjf4j.schema.ValidationOptions;
import org.sjf4j.schema.ValidationResult;

public class BowtieSjf4jValidator {

  public static void main(String[] args) {
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    new BowtieSjf4jValidator(System.out).serve(reader);
  }

  private static final List<String> DIALECTS =
      List.of("https://json-schema.org/draft/2020-12/schema",
              "https://json-schema.org/draft/2019-09/schema",
              "http://json-schema.org/draft-07/schema#");

  private static final Sjf4j JSONS = Sjf4j.builder().includeNulls(false).build();

  private final PrintStream output;
  private final String startResponseJson;
  private boolean started;
  private final String dialectOkJson =
      JSONS.toJsonString(new DialectResponse(true));
  private SchemaDialect dialect;
  private ValidationOptions annotatedOptions =
      ValidationOptions.builder().failFast(true).collectAnnotations(true).build();;

  public BowtieSjf4jValidator(PrintStream output) {
    this.output = output;
    this.startResponseJson = buildStartResponseJson();
  }

  private void serve(BufferedReader reader) {
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        Request req = BowtieBinder.INSTANCE.readRequest(line);
        switch (req.cmd()) {
        case "start" -> start(req);
        case "dialect" -> dialect(req);
        case "run" -> run(req);
        case "stop" -> System.exit(0);
        default ->
          throw new IllegalArgumentException("Unknown cmd [%s]".formatted(req.cmd()));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }


  private void start(Request req) {
    started = true;
    if (req.version() != 1) {
      throw new IllegalArgumentException(
          "Unsupported IHOP version [%d]".formatted(req.version()));
    }
    output.println(startResponseJson);
  }

  private String buildStartResponseJson() {
    try (InputStream is = getClass().getResourceAsStream("/META-INF/MANIFEST.MF")) {
      if (is == null) {
        throw new IllegalStateException("Missing MANIFEST.MF");
      }
      var attributes = new Manifest(is).getMainAttributes();

      String fullName = "%s-%s".formatted(
          attributes.getValue("Implementation-Group"),
          attributes.getValue("Implementation-Name"));

      StartResponse startResponse = new StartResponse(
          1, new Implementation(
              "java", fullName, attributes.getValue("Implementation-Version"),
              DIALECTS, "https://sjf4j.org",
              "https://github.com/sjf4j-projects/sjf4j",
              "https://github.com/sjf4j-projects/sjf4j/issues",
              "https://github.com/sjf4j-projects/sjf4j",
              System.getProperty("os.name"), System.getProperty("os.version"),
              Runtime.version().toString(), List.of()));
      return JSONS.toJsonString(startResponse);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void ensureStarted() {
    if (!started) {
      throw new IllegalArgumentException("Not started!");
    }
  }

  private void dialect(Request req) {
    ensureStarted();
    String uri = req.dialect();
    switch (uri) {
    case "https://json-schema.org/draft/2020-12/schema":
      dialect = SchemaDialect.DRAFT_2020_12;
      break;
    case "https://json-schema.org/draft/2019-09/schema":
      dialect = SchemaDialect.DRAFT_2019_09;
      break;
    case "https://json-schema.org/draft-07/schema":
    case "http://json-schema.org/draft-07/schema#":
      dialect = SchemaDialect.DRAFT_07;
      break;
    default:
      throw new IllegalArgumentException("Unsupported dialect: " + uri);
    }
    output.println(dialectOkJson);
  }

  private void run(Request req) {
    ensureStarted();
    if (dialect == null) {
      throw new IllegalArgumentException("No dialect configured!");
    }

    try {
      TestCase testCase = req.testCase();

      // External schemas belong to a single case, and different cases may
      // give the same URI different contents, so each case gets its own.
      SchemaRegistry registry = new SchemaRegistry(dialect);

      Map<String, Object> registryMap = testCase.registry();
      if (registryMap != null) {
        for (Map.Entry<String, Object> entry : registryMap.entrySet()) {
          URI id = URI.create(entry.getKey());
          registry.indexIfAbsent(id, JsonSchema.fromNode(entry.getValue()));
        }
      }

      JsonSchema schema = JsonSchema.fromNode(testCase.schema());
      SchemaPlan plan = schema.createPlan(registry);

      List<Test> tests = testCase.tests();
      List<TestResult> results = new ArrayList<>(tests.size());

      String outputType = req.output();
      switch (outputType) {
      case "flag":
        for (Test t : tests) {
          results.add(new TestResult(plan.isValid(t.instance()), null));
        }
        break;
      case "annotations":
        for (Test t : tests) {
          ValidationResult result = plan.validate(t.instance(), annotatedOptions);
          results.add(new TestResult(result.isValid(), buildAnnotations(result)));
        }
        break;
      default:
        throw new IllegalArgumentException("Unsupported output: " + outputType);
      }

      RunResponse response = new RunResponse(req.seq(), results);
      output.println(BowtieBinder.INSTANCE.writeAsString(response));
    } catch (Exception e) {
      output.println(JSONS.toJsonString(new RunErroredResponse(
          req.seq(), true, new ErrorContext(e.getMessage(), stackTraceToString(e)))));
    }
  }

  private static List<Annotation> buildAnnotations(ValidationResult result) {
    List<ValidationResult.Detail> details = result.getAnnotations();
    List<Annotation> annotations = new ArrayList(details.size());
    for (ValidationResult.Detail d : details) {
      String akLoc = d.getAbsoluteKeywordLocation();
      annotations.add(new Annotation(
          d.getKeyword(),
          d.getInstancePs().rootedPointerExpr(),
          akLoc == null ? "#" + d.getKeywordPs().rootedPointerExpr() : null,
          akLoc,
          d.getAnnotation()));
    }
    return annotations;
  }


  private static String stackTraceToString(Throwable t) {
    StringWriter sw = new StringWriter(512);
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }

  @CompiledBinder(backend = Backend.JACKSON2)
  public static interface BowtieBinder {
    BowtieBinder INSTANCE =
        CompiledInstances.create(BowtieBinder.class, new RuntimeContext(false));

    @Read Request readRequest(String line);
    @Write String writeAsString(RunResponse response);
  }

}




record Request(String cmd, Integer version, String dialect, Object seq,
               @NodeProperty("case") TestCase testCase, String output) {}

record TestCase(String description, String comment, Object schema,
                Map<String, Object> registry, List<Test> tests) {}

record Test(Object instance) {}


record StartResponse(int version, Implementation implementation) {}

record Implementation(String language, String name, String version,
                      List<String> dialects, String homepage, String documentation,
                      String issues, String source, String os, String os_version,
                      String language_version, List<Link> links) {}

record Link(String url, String description) {}


record DialectResponse(boolean ok) {}


record RunSkippedResponse(Object seq, boolean skipped, String message,
                          String issue_url) {}

record RunErroredResponse(Object seq, boolean errored, ErrorContext context) {}

record ErrorContext(String message, String traceback) {}

record RunResponse(Object seq, List<TestResult> results) {}

record TestResult(boolean valid, List<Annotation> annotations) {}

record Annotation(String keyword, String instanceLocation, String keywordLocation,
                  String absoluteKeywordLocation, Object annotation) {}
