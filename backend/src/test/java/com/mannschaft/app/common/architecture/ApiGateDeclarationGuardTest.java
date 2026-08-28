package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mannschaft.app.common.architecture.JavaSourceScanningUtils.maskCommentsAndLiterals;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * API の mapped method が feature gate または明示的な常時到達理由を宣言することを監査する番人。
 * HTTP は 3,503 件、STOMP は Chat の 2 件と VillageLobbyPresenceController の 3 件、計 5 件を走査する。
 */
class ApiGateDeclarationGuardTest {

    private static final Pattern CLASS_GATE = Pattern.compile("(?s)@(?:[\\w.]+\\.)?RequireFeature\\s*\\([^)]*\\)\\s*(?:public\\s+)?(?:final\\s+)?class\\s+");
    private static final Pattern CLASS_ALWAYS = Pattern.compile("(?s)@(?:[\\w.]+\\.)?AlwaysReachable\\s*\\([^)]*\\)\\s*(?:public\\s+)?(?:final\\s+)?class\\s+");
    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.]+)(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern ANNOTATED_METHOD = Pattern.compile(
            "(?s)((?:\\s*@(?:[\\w.]+)(?:\\s*\\([^)]*\\))?\\s*)+)(?:public|protected|private)\\s+[^{;=()]+\\([^;{}]*\\)\\s*(?:throws[^{}]+)?\\{");
    private static final Pattern REASON = Pattern.compile("\\breason\\s*=\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern CATEGORY = Pattern.compile("\\bcategory\\s*=\\s*(?:[\\w.]+\\.)?(CORE|PUBLIC_LIFELINE|GATE_CONTROL_PLANE|PLATFORM_INFRA)\\b");
    private static final Path FREEZE = Paths.get("src/test/resources/api_gate/api_gate_declaration_freeze.txt");

    @Test
    @org.junit.jupiter.api.DisplayName("API Gate 宣言台帳: type|FQCN|未宣言|総数の凍結と常時到達宣言を検証する")
    void mappedApisMatchThePerClassFreeze() throws IOException {
        Scan scan = scan();
        assertThat(scan.sourceCount()).isPositive();
        assertThat(scan.entries()).isNotEmpty();
        assertThat(scan.violations()).as("AlwaysReachable declaration violations: %s", scan.violations()).isEmpty();
        assertThat(freeze(scan.entries())).as("type|FQCN|undeclared|total; a new leak and a same-count swap must fail")
                .containsExactlyElementsOf(readFreeze());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("合成陽性対照: class/method feature gate と常時到達を coverage として認める")
    void positiveControlsAcceptClassAndMethodFeatureGatesAndAlwaysReachable() {
        assertThat(analyze("sample.ClassGate", """
                @RequireFeature("FEATURE_A") public class ClassGate {
                  @GetMapping public void get() {} @PostMapping public void post() {}
                }""")).allMatch(Entry::declared);
        assertThat(analyze("sample.MethodGate", """
                public class MethodGate {
                  @RequireFeature("FEATURE_A") @GetMapping public void get() {}
                  @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "bootstrap")
                  @MessageMapping("/send") public void send() {}
                }""")).allMatch(Entry::declared);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("合成対照: class-level RequestMapping を最初の非 mapping method へ誤帰属しない")
    void classRequestMappingIsNotAssignedToTheFirstNonMappedMethod() {
        assertThat(analyze("sample.NoFalsePositive", """
                @RequestMapping("/base") public class NoFalsePositive {
                  public void helper() {} @GetMapping("/real") public void endpoint() {}
                }""")).extracting(Entry::type).containsExactly(Type.HTTP);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("合成陰性対照: 不正な常時到達宣言を拒否する")
    void negativeControlsRejectInvalidAlwaysReachableDeclarations() {
        assertThat(violations("sample.Bad", """
                @RequireFeature("FEATURE_A") public class Bad {
                  @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "") @GetMapping public void gated() {}
                  @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "helper") public void helper() {}
                }"""))
                .anySatisfy(v -> assertThat(v).contains("reason"))
                .anySatisfy(v -> assertThat(v).contains("class gate"))
                .anySatisfy(v -> assertThat(v).contains("mapped method"));
        assertThat(violations("sample.BadCategory", """
                public class BadCategory {
                  @AlwaysReachable(reason = "missing category") @GetMapping public void get() {}
                }""")).anySatisfy(v -> assertThat(v).contains("category"));
        assertThat(violations("sample.Double", """
                public class Double {
                  @RequireFeature("FEATURE_A") @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "double")
                  @GetMapping public void get() {}
        }""")).anySatisfy(v -> assertThat(v).contains("cannot share a method"));
    }

    static Scan scan() throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        int[] sourceCount = {0};
        try (Stream<Path> paths = Files.walk(sourceRoot())) {
            paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    sourceCount[0]++;
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    String fqcn = fqcn(path);
                    entries.addAll(analyze(fqcn, source));
                    violations.addAll(violations(fqcn, source));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
        return new Scan(entries, violations, sourceCount[0]);
    }

    static List<Entry> analyze(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        boolean classGate = CLASS_GATE.matcher(masked).find();
        List<Entry> entries = new ArrayList<>();
        Matcher methods = ANNOTATED_METHOD.matcher(masked);
        while (methods.find()) {
            List<String> names = annotationNames(methods.group(1));
            Type type = typeOf(names);
            if (type != null) entries.add(new Entry(type, fqcn, classGate || names.contains("RequireFeature") || names.contains("AlwaysReachable")));
        }
        return entries;
    }

    static List<String> violations(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        if (CLASS_ALWAYS.matcher(masked).find()) violations.add(fqcn + ": AlwaysReachable is method-level only");
        boolean classGate = CLASS_GATE.matcher(masked).find();
        Matcher always = ANNOTATION.matcher(masked);
        while (always.find()) {
            if (!simpleName(always.group(1)).equals("AlwaysReachable")) continue;
            MethodAnnotations owner = ownerOf(always.start(), masked);
            if (owner == null || typeOf(owner.names()) == null) {
                violations.add(fqcn + ": AlwaysReachable requires a mapped method");
                continue;
            }
            String args = source.substring(always.start(2), always.end(2));
            Matcher reason = REASON.matcher(args);
            if (!reason.find() || reason.group(1).isBlank()) violations.add(fqcn + ": AlwaysReachable reason must be nonblank");
            if (!CATEGORY.matcher(args).find()) violations.add(fqcn + ": AlwaysReachable category is invalid");
            if (classGate) violations.add(fqcn + ": class gate cannot be overridden by AlwaysReachable");
            if (owner.names().contains("RequireFeature")) violations.add(fqcn + ": RequireFeature and AlwaysReachable cannot share a method");
        }
        return violations;
    }

    private static MethodAnnotations ownerOf(int annotationOffset, String masked) {
        Matcher methods = ANNOTATED_METHOD.matcher(masked);
        while (methods.find()) {
            if (annotationOffset >= methods.start(1) && annotationOffset < methods.end(1)) return new MethodAnnotations(annotationNames(methods.group(1)));
        }
        return null;
    }

    private static List<String> freeze(List<Entry> entries) {
        Map<String, int[]> counts = new TreeMap<>();
        for (Entry entry : entries) {
            int[] count = counts.computeIfAbsent(entry.type() + "|" + entry.fqcn(), ignored -> new int[2]);
            if (!entry.declared()) count[0]++;
            count[1]++;
        }
        return counts.entrySet().stream().map(e -> e.getKey() + "|" + e.getValue()[0] + "|" + e.getValue()[1]).toList();
    }

    private static List<String> readFreeze() throws IOException {
        Path path = Files.exists(FREEZE) ? FREEZE : Paths.get("backend").resolve(FREEZE);
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank() && !line.startsWith("#")).sorted().toList();
    }

    private static List<String> annotationNames(String annotations) {
        List<String> names = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(annotations);
        while (matcher.find()) names.add(simpleName(matcher.group(1)));
        return names;
    }

    private static Type typeOf(List<String> names) {
        if (names.contains("MessageMapping")) return Type.STOMP;
        return names.stream().anyMatch(name -> switch (name) {
            case "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping" -> true;
            default -> false;
        }) ? Type.HTTP : null;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static Path sourceRoot() {
        for (String candidate : List.of("src/main/java", "backend/src/main/java")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) return path;
        }
        throw new IllegalStateException("src/main/java was not found");
    }

    private static String fqcn(Path path) {
        String name = sourceRoot().relativize(path).toString().replace('\\', '.');
        return name.substring(0, name.length() - ".java".length());
    }

    enum Type { HTTP, STOMP }
    record Entry(Type type, String fqcn, boolean declared) { }
    record MethodAnnotations(List<String> names) { }
    record Scan(List<Entry> entries, List<String> violations, int sourceCount) { }
}
