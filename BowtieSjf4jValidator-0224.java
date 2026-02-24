import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.jar.Manifest;
import org.sjf4j.JsonObject;
import org.sjf4j.Sjf4j;
import org.sjf4j.annotation.node.NodeProperty;
import org.sjf4j.schema.JsonSchema;
import org.sjf4j.schema.SchemaStore;


public class BowtieSjf4jValidator {

  public static void main(String[] args) {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    new BowtieSjf4jValidator(System.out).run(reader);
  }

  private static final Set<String> DIALECTS =
          Set.of("https://json-schema.org/draft/2020-12/schema");

  private final PrintStream output;
  private final String startResponseJson;
  private final String dialectOkJson = Sjf4j.toJsonString(new DialectResponse(true));
  private final String dialectNoJson = Sjf4j.toJsonString(new DialectResponse(false));
  private boolean started;

  public BowtieSjf4jValidator(PrintStream output) {
    this.output = output;
    this.startResponseJson = buildStartResponseJson();
  }

  private void run(BufferedReader reader) {
    try {
      String line;
      while ((line = reader.readLine()) != null) {
        handle(line);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(String data) {
    JsonObject jo = JsonObject.fromJson(data);
    String cmd = jo.getString("cmd");
    switch (cmd) {
      case "start" -> start(jo);
      case "dialect" -> dialect(jo);
      case "run" -> runCase(jo);
      case "stop" -> System.exit(0);
      default -> throw new IllegalArgumentException("Unknown cmd [%s]".formatted(cmd));
    }
  }

  private void start(JsonObject jo) {
    started = true;
    StartRequest req = jo.toNode(StartRequest.class);
    if (req.version() != 1) {
      throw new IllegalArgumentException("Unsupported IHOP version [%d]".formatted(req.version()));
    }
    output.println(startResponseJson);
  }

  private void dialect(JsonObject jo) {
    ensureStarted();
    DialectRequest req = Sjf4j.fromNode(jo, DialectRequest.class);
    output.println(DIALECTS.contains(req.dialect()) ? dialectOkJson : dialectNoJson);
  }

  private void runCase(JsonObject jo) {
    ensureStarted();
    RunRequest req = Sjf4j.fromNode(jo, RunRequest.class);

    try {
      TestCase tc = req.testCase();
      SchemaStore store = new SchemaStore();

      JsonObject registry = tc.registry();
      if (registry != null) {
        for (Map.Entry<String, Object> e : registry.entrySet()) {
          store.register(URI.create(e.getKey()), JsonSchema.fromNode(e.getValue()));
        }
      }

      JsonSchema schema = JsonSchema.fromNode(tc.schema());
      schema.compile(store);

      List<Test> tests = tc.tests();
      List<TestResult> results = new ArrayList<>(tests.size());
      for (Test t : tests) {
        results.add(new TestResult(schema.isValid(t.instance())));
      }

      output.println(Sjf4j.toJsonString(new RunResponse(req.seq(), results)));
    } catch (Exception e) {
      output.println(Sjf4j.toJsonString(
              new RunErroredResponse(req.seq(), true,
                      new ErrorContext(e.getMessage(), stackTraceToString(e)))));
    }
  }

  private void ensureStarted() {
    if (!started) {
      throw new IllegalArgumentException("Not started!");
    }
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
              new ArrayList<>(DIALECTS),
              "https://sjf4j.org",
              "https://github.com/sjf4j-projects/sjf4j",
              "https://github.com/sjf4j-projects/sjf4j/issues",
              "https://github.com/sjf4j-projects/sjf4j",
              System.getProperty("os.name"),
              System.getProperty("os.version"),
              Runtime.version().toString(),
              List.of()));
      return Sjf4j.toJsonString(startResponse);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String stackTraceToString(Throwable t) {
    StringWriter sw = new StringWriter(512);
    t.printStackTrace(new PrintWriter(sw));
    return sw.toString();
  }
}


record StartRequest(int version) {}

record StartResponse(int version, Implementation implementation) {}

record DialectRequest(String dialect) {}

record DialectResponse(boolean ok) {}

record RunRequest(Object seq, @NodeProperty("case") TestCase testCase) {}

record RunResponse(Object seq, List<TestResult> results) {}

record RunSkippedResponse(Object seq, boolean skipped, String message,
                          String issue_url) {}

record RunErroredResponse(Object seq, boolean errored, ErrorContext context) {}

record ErrorContext(String message, String traceback) {}

record Implementation(String language, String name, String version,
                      List<String> dialects, String homepage, String documentation,
                      String issues, String source, String os, String os_version,
                      String language_version, List<Link> links) {}

record Link(String url, String description) {}

record TestCase(String description, String comment, Object schema,
                JsonObject registry, List<Test> tests) {}

record Test(String description, String comment, Object instance, boolean valid) {}

record TestResult(boolean valid) {}


