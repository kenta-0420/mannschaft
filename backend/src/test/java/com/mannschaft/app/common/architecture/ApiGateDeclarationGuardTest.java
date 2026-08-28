package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.mannschaft.app.common.architecture.JavaSourceScanningUtils.maskCommentsAndLiterals;
import static org.assertj.core.api.Assertions.assertThat;

/** HTTP mapping 3,503 件と STOMP {@code @MessageMapping} 2 件を走査する API Gate 宣言番人。 */
class ApiGateDeclarationGuardTest {

    private static final Pattern CLASS_GATE = Pattern.compile("(?s)@(?:[\\w.]+\\.)?RequireFeature(?:\\s*\\([^)]*\\))?\\s*(?:public\\s+)?(?:final\\s+)?class\\s+");
    private static final Pattern CLASS_ALWAYS = Pattern.compile("(?s)@(?:[\\w.]+\\.)?AlwaysReachable(?:\\s*\\([^)]*\\))?\\s*(?:public\\s+)?(?:final\\s+)?class\\s+");
    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.]+)(?:\\s*\\(([^)]*)\\))?");
    private static final Pattern METHOD = Pattern.compile("(?:public|protected|private)\\s+[^{;=()]+\\([^;{}]*\\)\\s*(?:throws[^{}]+)?\\{");
    private static final Pattern REASON = Pattern.compile("\\breason\\s*=\\s*\\\"([^\\\"]*)\\\"");
    private static final Pattern CATEGORY = Pattern.compile("\\bcategory\\s*=\\s*(?:[\\w.]+\\.)?(CORE|PUBLIC_LIFELINE|GATE_CONTROL_PLANE|PLATFORM_INFRA)\\b");
    // CMP-260827-0215 第一陣の棚卸し。type|FQCN|未宣言|総数のうち type 集計値を固定する。
    // Controller への段階的な宣言付与は後続陣の担当であり、未宣言数だけを減らす変更は許容する。
    private static final Map<Type, Counts> FROZEN = Map.of(Type.HTTP, new Counts(3503, 3503), Type.STOMP, new Counts(2, 2));

    @Test
    void api入口はgate又は常時到達を宣言し凍結値から逸脱しない() throws IOException {
        Scan scan = scan();
        assertThat(scan.sourceCount()).as("走査 source が 0 件ではないこと").isPositive();
        assertThat(scan.entries()).as("mapped method が 0 件ではないこと").isNotEmpty();
        assertThat(scan.violations()).as("常時到達宣言の違反\n%s", scan.violations()).isEmpty();
        assertThat(summary(scan.entries())).as("type|FQCN|未宣言|総数の凍結。新規漏れと同数相殺を許さない")
                .containsExactlyInAnyOrderEntriesOf(FROZEN);
    }

    @Test
    void 合成陽性対照_宣言の各経路をcoverageとして認める() {
        assertThat(analyze("sample.ClassGate", """
                @RequireFeature("FEATURE_A") public class ClassGate {
                    @GetMapping public void get() {} @PostMapping public void post() {}
                }""")).allMatch(Entry::declared);
        assertThat(analyze("sample.MethodGate", """
                public class MethodGate {
                    @RequireFeature("FEATURE_A") @GetMapping public void get() {}
                    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "起動に必要")
                    @MessageMapping("/send") public void send() {}
                }""")).allMatch(Entry::declared);
    }

    @Test
    void 合成陰性対照_常時到達の必須要素とclassGate上書きを拒む() {
        assertThat(alwaysReachableViolations("sample.Bad", """
                @RequireFeature("FEATURE_A") public class Bad {
                    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "")
                    @GetMapping public void get() {}
                }"""))
                .anySatisfy(v -> assertThat(v).contains("reason は非空必須"))
                .anySatisfy(v -> assertThat(v).contains("上書きは禁止"));
        assertThat(alwaysReachableViolations("sample.BadCategory", """
                public class BadCategory {
                    @AlwaysReachable(reason = "到達を維持する") @GetMapping public void get() {}
                }"""))
                .anySatisfy(v -> assertThat(v).contains("category が不正"));
    }

    static Scan scan() throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        int[] sourceCount = {0};
        try (Stream<Path> paths = Files.walk(sourceRoot())) {
            paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                try {
                    sourceCount[0]++;
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    entries.addAll(analyze(fqcn(path), source));
                    violations.addAll(alwaysReachableViolations(fqcn(path), source));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return new Scan(entries, violations, sourceCount[0]);
    }

    static List<Entry> analyze(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        boolean classGate = CLASS_GATE.matcher(masked).find();
        List<Entry> entries = new ArrayList<>();
        Matcher methods = METHOD.matcher(masked);
        int previous = 0;
        while (methods.find()) {
            List<String> annotations = annotationNames(masked.substring(previous, methods.start()));
            previous = methods.end();
            Type type = typeOf(annotations);
            if (type != null) {
                boolean declared = classGate || annotations.contains("RequireFeature") || annotations.contains("AlwaysReachable");
                entries.add(new Entry(type, fqcn, declared));
            }
        }
        return entries;
    }

    static List<String> alwaysReachableViolations(String fqcn, String source) {
        String masked = maskCommentsAndLiterals(source);
        List<String> violations = new ArrayList<>();
        if (CLASS_ALWAYS.matcher(masked).find()) violations.add(fqcn + " : @AlwaysReachable は method-level のみ");
        boolean classGate = CLASS_GATE.matcher(masked).find();
        Matcher annotations = ANNOTATION.matcher(masked);
        while (annotations.find()) {
            if (!simpleName(annotations.group(1)).equals("AlwaysReachable")) continue;
            Matcher methods = METHOD.matcher(masked);
            if (!methods.find(annotations.end()) || masked.substring(annotations.end(), methods.start()).contains("class")) {
                violations.add(fqcn + " : @AlwaysReachable は method-level のみ");
                continue;
            }
            String args = source.substring(annotations.start(2), annotations.end(2));
            Matcher reason = REASON.matcher(args);
            if (!reason.find() || reason.group(1).isBlank()) violations.add(fqcn + " : reason は非空必須");
            if (!CATEGORY.matcher(args).find()) violations.add(fqcn + " : category が不正");
            if (classGate) violations.add(fqcn + " : class-level @RequireFeature 下の @AlwaysReachable 上書きは禁止");
        }
        return violations;
    }

    private static Map<Type, Counts> summary(List<Entry> entries) {
        Map<Type, Counts> result = new EnumMap<>(Type.class);
        for (Type type : Type.values()) {
            int total = (int) entries.stream().filter(e -> e.type() == type).count();
            int undeclared = (int) entries.stream().filter(e -> e.type() == type && !e.declared()).count();
            result.put(type, new Counts(total, undeclared));
        }
        return result;
    }

    private static List<String> annotationNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher annotations = ANNOTATION.matcher(source);
        while (annotations.find()) names.add(simpleName(annotations.group(1)));
        return names;
    }

    private static Type typeOf(List<String> annotations) {
        if (annotations.contains("MessageMapping")) return Type.STOMP;
        return annotations.stream().anyMatch(name -> switch (name) {
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
        throw new IllegalStateException("src/main/java が見つからない");
    }

    private static String fqcn(Path path) {
        String result = sourceRoot().relativize(path).toString().replace('\\', '.');
        return result.substring(0, result.length() - 5);
    }

    enum Type { HTTP, STOMP }
    record Counts(int total, int undeclared) { }
    record Entry(Type type, String fqcn, boolean declared) { }
    record Scan(List<Entry> entries, List<String> violations, int sourceCount) { }
}
