package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mannschaft.app.common.architecture.JavaSourceScanningUtils.maskCommentsAndLiterals;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * API の mapped method が feature gate または明示的な常時到達理由を宣言することを監査する番人。
 * HTTP は 3,546 件、STOMP は Chat の 2 件と VillageLobbyPresenceController の 3 件、計 5 件を走査する。
 */
class ApiGateDeclarationGuardTest {

    private static final Pattern CLASS_ANNOTATIONS = Pattern.compile("(?s)((?:\\s*@(?:[\\w.]+)(?:\\s*\\([^)]*\\))?\\s*)+)(?:public\\s+)?(?:final\\s+)?class\\s+");
    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.]+)(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern REASON = Pattern.compile("\\breason\\s*=\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern CATEGORY = Pattern.compile("\\bcategory\\s*=\\s*(?:[\\w.]+\\.)?(CORE|PUBLIC_LIFELINE|GATE_CONTROL_PLANE|PLATFORM_INFRA)\\b");
    private static final Path FREEZE = Paths.get("src/test/resources/api_gate/api_gate_declaration_freeze.txt");

    @Test
    @org.junit.jupiter.api.DisplayName("API Gate 宣言台帳: type|FQCN|未宣言|総数の凍結と常時到達宣言を検証する")
    void mappedApisMatchThePerClassFreeze() throws IOException {
        Scan scan = scan();
        assertThat(scan.sourceCount()).isPositive();
        assertThat(scan.entries()).isNotEmpty();
        assertThat(scan.entries().stream().filter(entry -> entry.type() == Type.HTTP).count())
                .as("HTTP mapped method の走査総数。parser 退行を台帳比較とは独立に検知する")
                .isEqualTo(3556);   // main 3542 + #3100 3件 + 当PR(#3112) retention-expired 1件
                                    // + 柱③-A JoinRequestController 新設10件（CMP-260901-1538）
        assertThat(scan.entries().stream().filter(entry -> entry.type() == Type.STOMP).count())
                .as("STOMP @MessageMapping の走査総数。Chat 2件と VillageLobbyPresence 3件")
                .isEqualTo(5);
        assertThat(scan.violations()).as("AlwaysReachable declaration violations: %s", scan.violations()).isEmpty();
        String actual = String.join("\n", freeze(scan.entries()));
        String expected = String.join("\n", readFreeze());
        if (!actual.equals(expected)) {
            throw new AssertionError("type|FQCN|undeclared|total の全台帳が不一致\nACTUAL_FREEZE_BEGIN\n"
                    + actual + "\nACTUAL_FREEZE_END");
        }
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
    @org.junit.jupiter.api.DisplayName("合成対照: 他のController注釈に挟まれたclass-level宣言を認識する")
    void classLevelDeclarationsSurviveOtherControllerAnnotations() {
        assertThat(analyze("sample.ClassGate", """
                @RequireFeature("FEATURE_A") @RestController @RequestMapping("/api")
                public class ClassGate { @GetMapping public void get() {} }"""))
                .allMatch(Entry::declared);
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
    @org.junit.jupiter.api.DisplayName("合成対照: 可視性・複数行・他annotation・入れ子括弧を越えて mapping と method を結ぶ")
    void mappingAnnotationAndMethodAreBoundPrecisely() {
        assertThat(analyze("sample.Multiline", """
                @RequestMapping("/base") class Multiline {
                  @Deprecated
                  @GetMapping(path = {"/a", "/b"})
                  protected String endpoint(
                      String value) { return value; }
                  @PostMapping(value = "/nested", consumes = {"application/json"})
                  private void nested() {}
                }""")).extracting(Entry::type).containsExactly(Type.HTTP, Type.HTTP);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("合成対照: Windows/Linux のパス区切りを同じFQCNへ正規化する")
    void sourcePathsUsePortableFqcnSeparators() {
        assertThat(fqcnFromRelativePath("com\\mannschaft\\app\\SampleController.java"))
                .isEqualTo("com.mannschaft.app.SampleController");
        assertThat(fqcnFromRelativePath("com/mannschaft/app/SampleController.java"))
                .isEqualTo("com.mannschaft.app.SampleController");
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
        assertThat(violations("sample.ClassAlways", """
                @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "class declaration")
                @RestController @RequestMapping("/api") public class ClassAlways {
                  @GetMapping public void get() {}
                }""")).anySatisfy(v -> assertThat(v).contains("method-level only"));
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
        Path sourceRoot = sourceRoot();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    sourceCount[0]++;
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    if (!mightContainMappedApi(source)) return;
                    String fqcn = fqcn(sourceRoot, path);
                    entries.addAll(analyze(fqcn, source));
                    if (source.contains("AlwaysReachable")) {
                        violations.addAll(violations(fqcn, source));
                    }
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        }
        return new Scan(entries, violations, sourceCount[0]);
    }

    private static boolean mightContainMappedApi(String source) {
        return source.contains("RequestMapping") || source.contains("GetMapping")
                || source.contains("PostMapping") || source.contains("PutMapping")
                || source.contains("PatchMapping") || source.contains("DeleteMapping")
                || source.contains("MessageMapping") || source.contains("AlwaysReachable");
    }

    static List<Entry> analyze(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        boolean classGate = hasClassAnnotation(masked, "RequireFeature");
        List<Entry> entries = new ArrayList<>();
        for (MethodAnnotations method : methodAnnotations(masked).values()) {
            Type type = typeOf(method.names());
            if (type != null) {
                entries.add(new Entry(type, fqcn,
                        classGate || method.names().contains("RequireFeature") || method.names().contains("AlwaysReachable")));
            }
        }
        return entries;
    }

    private static MethodAnnotations methodAfter(int offset, String masked) {
        int cursor = skipAnnotationArguments(offset, masked);
        List<String> names = new ArrayList<>();
        while (true) {
            cursor = skipWhitespace(cursor, masked);
            if (cursor >= masked.length() || masked.charAt(cursor) != '@') break;
            Matcher annotation = ANNOTATION.matcher(masked);
            if (!annotation.find(cursor) || annotation.start() != cursor) break;
            names.add(simpleName(annotation.group(1)));
            cursor = skipAnnotationArguments(annotation.end(), masked);
        }
        int wordStart = cursor;
        for (int i = cursor; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') return new MethodAnnotations(names, i, Set.of());
            if (c == '{' || c == ';' || c == '=' || c == '}') return null;
            if (Character.isWhitespace(c)) {
                String word = masked.substring(wordStart, i).trim();
                if (word.equals("class") || word.equals("interface") || word.equals("record") || word.equals("enum")) return null;
                wordStart = i + 1;
            }
        }
        return null;
    }

    private static int skipAnnotationArguments(int cursor, String text) {
        cursor = skipWhitespace(cursor, text);
        if (cursor >= text.length() || text.charAt(cursor) != '(') return cursor;
        int depth = 0;
        for (int i = cursor; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            if (text.charAt(i) == ')' && --depth == 0) return i + 1;
        }
        return text.length();
    }

    private static int skipWhitespace(int cursor, String text) {
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }

    static List<String> violations(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        if (hasClassAnnotation(masked, "AlwaysReachable")) violations.add(fqcn + ": AlwaysReachable is method-level only");
        boolean classGate = hasClassAnnotation(masked, "RequireFeature");
        Map<Integer, MethodAnnotations> methods = methodAnnotations(masked);
        Matcher always = ANNOTATION.matcher(masked);
        while (always.find()) {
            if (!simpleName(always.group(1)).equals("AlwaysReachable")) continue;
            MethodAnnotations owner = methods.values().stream()
                    .filter(method -> method.annotationOffsets().contains(always.start()))
                    .findFirst().orElse(null);
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

    private static Map<Integer, MethodAnnotations> methodAnnotations(String masked) {
        Map<Integer, List<String>> names = new TreeMap<>();
        Map<Integer, Set<Integer>> offsets = new TreeMap<>();
        Matcher annotations = ANNOTATION.matcher(masked);
        while (annotations.find()) {
            MethodAnnotations method = methodAfter(annotations.end(), masked);
            if (method == null) continue;
            names.computeIfAbsent(method.openParen(), ignored -> new ArrayList<>()).add(simpleName(annotations.group(1)));
            offsets.computeIfAbsent(method.openParen(), ignored -> new HashSet<>()).add(annotations.start());
        }
        Map<Integer, MethodAnnotations> result = new TreeMap<>();
        names.forEach((openParen, methodNames) -> result.put(openParen,
                new MethodAnnotations(methodNames, openParen, offsets.get(openParen))));
        return result;
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

    private static boolean hasClassAnnotation(String masked, String annotation) {
        Matcher matcher = CLASS_ANNOTATIONS.matcher(masked);
        return matcher.find() && annotationNames(matcher.group(1)).contains(annotation);
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

    private static String fqcn(Path sourceRoot, Path path) {
        return fqcnFromRelativePath(sourceRoot.relativize(path).toString());
    }

    private static String fqcnFromRelativePath(String relativePath) {
        String name = relativePath.replace('\\', '.').replace('/', '.');
        return name.substring(0, name.length() - ".java".length());
    }

    enum Type { HTTP, STOMP }
    record Entry(Type type, String fqcn, boolean declared) { }
    record MethodAnnotations(List<String> names, int openParen, Set<Integer> annotationOffsets) { }
    record Scan(List<Entry> entries, List<String> violations, int sourceCount) { }
}
