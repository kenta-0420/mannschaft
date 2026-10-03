package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;

/**
 * 番人: 本番クラス全体の取り込みは共有ホルダ {@link ProductionClasses} に一本化する（CMP-261002-1606）。
 *
 * <p>全量 CI の shard 5 が {@code Java heap space} で落ちていた原因は、テストクラスが本番全体を
 * {@code ClassFileImporter} で手動取り込みし、static フィールド等で何コピーも保持していたこと。
 * この番人は、次のいずれかを持つテストクラス（ホルダ自身を除く）を違反とする。
 * <ul>
 *   <li>(a) {@code JavaClasses} 型の static フィールド（テストクラスの寿命を超えて取り込み結果を
 *       掴み続ける。fixture だけの取り込みでも static 保持は禁止し、instance フィールドか
 *       ローカル変数にする）。</li>
 *   <li>(b) 本番全体の手動取り込み。具体的には
 *       <ul>
 *         <li>{@code importPackages}/{@code importPackagesOf} に本番ルート
 *             （{@code com.mannschaft.app}）またはその祖先（{@code com.mannschaft}・{@code com}・空文字）
 *             を渡す呼び出し。</li>
 *         <li>{@code importPath(s)}/{@code importClasspath}/{@code importLocations}/
 *             {@code importUrl(s)}/{@code importJar(s)}（場所単位の一括取り込み。本番全体に
 *             相当するため、場所指定の取り込みは一律禁止）。</li>
 *       </ul>
 *       {@code importClasses}（クラスを列挙する取り込み）と、本番の一部パッケージ・fixture
 *       パッケージに限った {@code importPackages} は許可する。</li>
 * </ul>
 *
 * <p><b>検出方式</b>: テスト出力ディレクトリ（{@code build/classes/java/test}）の .class を
 * Spring 同梱の ASM で1つずつ流し読みする。ArchUnit でテストツリー全体を取り込むと番人自身が
 * メモリを食うため使わない。{@code importPackages} の引数は、呼び出し直前までに同じメソッド内で
 * 積まれた文字列定数（LDC）と、GETSTATIC で読んだ static フィールドの {@code <clinit>} 初期化定数
 * （{@code String[]} 定数配列を含む）から判定する。文字列連結などで動的に組み立てた引数は判定できない。
 *
 * <p>この番人は凍結ストアを使わない（違反は出陣で全返済する）。自己検証用の違反見本は
 * {@value #SELF_FIXTURES_PACKAGE} に置き、本番の走査からは除外する。
 */
@DisplayName("番人: 本番クラス全体の取り込みは共有ホルダに一本化（CMP-261002-1606）")
class ProductionClassImportGuardTest {

    /** 自己検証用の違反見本パッケージ（本番の走査から除外する）。 */
    static final String SELF_FIXTURES_PACKAGE =
            "com.mannschaft.app.common.architecture.fixtures.importguard";

    private static final String HOLDER = ProductionClasses.class.getName();
    private static final String PRODUCTION_ROOT = ProductionClasses.PRODUCTION_ROOT;

    private static final String IMPORTER_OWNER = "com/tngtech/archunit/core/importer/ClassFileImporter";
    private static final String JAVA_CLASSES_DESC = "Lcom/tngtech/archunit/core/domain/JavaClasses;";
    private static final Set<String> PACKAGE_IMPORTS = Set.of("importPackages", "importPackagesOf");
    private static final Set<String> LOCATION_IMPORTS = Set.of(
            "importPath", "importPaths", "importClasspath", "importLocations",
            "importUrl", "importUrls", "importJar", "importJars");

    /** 違反の種別。 */
    enum Kind {
        STATIC_JAVA_CLASSES_FIELD("(a) JavaClasses 型の static フィールド"),
        WHOLE_PRODUCTION_IMPORT("(b) 本番全体の手動取り込み");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 1件の違反。 */
    record Violation(Kind kind, String className, String member, int line, String detail) {
        String describe() {
            return kind.label + ": " + className + "#" + member
                    + (line > 0 ? " (line " + line + ")" : "") + " — " + detail;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // AC-5: 本番（テストツリー全体）の判定
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: ホルダ以外のテストクラスは static JavaClasses を持たず、本番全体を手動取り込みしない")
    void AC5_ホルダ以外は本番全体を手動取り込みしない() {
        Path testOutput = testClassesRoot();
        List<Path> classFiles = classFilesUnder(testOutput);
        assertThat(classFiles)
                .as("テスト出力 %s の .class が少なすぎる（走査が空振りしていないか）", testOutput)
                .hasSizeGreaterThan(100);

        String selfFixturesDir = SELF_FIXTURES_PACKAGE.replace('.', '/') + "/";
        List<Violation> violations = new ArrayList<>();
        for (Path file : classFiles) {
            String relative = testOutput.relativize(file).toString().replace('\\', '/');
            if (relative.startsWith(selfFixturesDir)) {
                continue;
            }
            violations.addAll(analyze(readBytes(file)));
        }

        assertThat(violations)
                .as(() -> "本番全体の取り込みは ProductionClasses.get() を使うこと（CMP-261002-1606）。違反 "
                        + violations.size() + " 件:\n" + violations.stream()
                                .sorted(Comparator.comparing(Violation::className)
                                        .thenComparing(Violation::line))
                                .map(Violation::describe)
                                .collect(Collectors.joining("\n")))
                .isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    // AC-6: 番人の自己検証（違反見本を検出し、許可形を検出しない）
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-6: static JavaClasses フィールドを持つ見本を (a) として検出する")
    void AC6_staticなJavaClassesフィールドを検出する() {
        List<Violation> violations = analyzeFixture("StaticJavaClassesFieldSample");

        assertThat(violations)
                .extracting(Violation::kind, Violation::member)
                .containsExactly(tuple(Kind.STATIC_JAVA_CLASSES_FIELD, "classes"));
    }

    @Test
    @DisplayName("AC-6: 本番ルートを文字列定数で importPackages する見本を (b) として検出する")
    void AC6_本番ルートの文字列定数取り込みを検出する() {
        assertDetectedAsWholeImport("WholeProductionImportSample");
    }

    @Test
    @DisplayName("AC-6: 本番ルートを static 定数配列経由で importPackages する見本を (b) として検出する")
    void AC6_定数配列経由の本番ルート取り込みを検出する() {
        assertDetectedAsWholeImport("WholeProductionImportViaArrayConstantSample");
    }

    @Test
    @DisplayName("AC-6: ルートパッケージのクラスを importPackagesOf する見本を (b) として検出する")
    void AC6_ルートパッケージのクラス起点の取り込みを検出する() {
        assertDetectedAsWholeImport("WholeProductionImportPackagesOfSample");
    }

    @Test
    @DisplayName("AC-6: importPath で場所単位に取り込む見本を (b) として検出する")
    void AC6_場所単位の取り込みを検出する() {
        assertDetectedAsWholeImport("WholeProductionImportPathSample");
    }

    @Test
    @DisplayName("AC-6: fixture・本番の一部だけを instance フィールド／ローカル変数で取り込む見本は検出しない")
    void AC6_fixture限定のinstance取り込みは検出しない() {
        assertThat(analyzeFixture("FixtureOnlyInstanceImportSample")).isEmpty();
    }

    @Test
    @DisplayName("AC-6: 共有ホルダ ProductionClasses 自身は検出しない")
    void AC6_共有ホルダ自身は検出しない() {
        List<Violation> violations = new ArrayList<>(analyze(readClass(HOLDER)));
        violations.addAll(analyze(readClass(HOLDER + "$Memoizer")));

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC-6: 違反見本パッケージ全体では見本の数だけ違反クラスが出る（見本の取りこぼし検出）")
    void AC6_違反見本パッケージの違反クラス一覧() {
        Path dir = testClassesRoot().resolve(SELF_FIXTURES_PACKAGE.replace('.', '/'));
        Set<String> violatingClasses = classFilesUnder(dir).stream()
                .flatMap(file -> analyze(readBytes(file)).stream())
                .map(v -> v.className().substring(v.className().lastIndexOf('.') + 1))
                .collect(Collectors.toSet());

        assertThat(violatingClasses).containsExactlyInAnyOrder(
                "StaticJavaClassesFieldSample",
                "WholeProductionImportSample",
                "WholeProductionImportViaArrayConstantSample",
                "WholeProductionImportPackagesOfSample",
                "WholeProductionImportPathSample");
    }

    private static void assertDetectedAsWholeImport(String simpleName) {
        List<Violation> violations = analyzeFixture(simpleName);
        assertThat(violations)
                .as("%s は (b) として検出されること", simpleName)
                .isNotEmpty()
                .allSatisfy(v -> assertThat(v.kind()).isEqualTo(Kind.WHOLE_PRODUCTION_IMPORT));
    }

    private static List<Violation> analyzeFixture(String simpleName) {
        return analyze(readClass(SELF_FIXTURES_PACKAGE + "." + simpleName));
    }

    // ══════════════════════════════════════════════════════════════
    // 判定ロジック
    // ══════════════════════════════════════════════════════════════

    /** 1クラス分のバイトコードを判定し、違反を返す。ホルダ（とその入れ子クラス）は常に違反なし。 */
    static List<Violation> analyze(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        String className = reader.getClassName().replace('/', '.');
        if (className.equals(HOLDER) || className.startsWith(HOLDER + "$")) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value) {
                if ((access & Opcodes.ACC_STATIC) != 0 && JAVA_CLASSES_DESC.equals(descriptor)) {
                    violations.add(new Violation(Kind.STATIC_JAVA_CLASSES_FIELD, className, name, 0,
                            "JavaClasses をテストクラスの static で保持している"));
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                    String signature, String[] exceptions) {
                return new ImportCallVisitor(className, methodName, violations);
            }
        }, ClassReader.SKIP_FRAMES);
        return violations;
    }

    /** メソッド内の ClassFileImporter 呼び出しを、直前に積まれた定数とともに判定する。 */
    private static final class ImportCallVisitor extends MethodVisitor {

        private final String className;
        private final String methodName;
        private final List<Violation> violations;
        /** 直前の取り込み呼び出し以降に積まれた定数（String / Type）。 */
        private final List<Object> pending = new ArrayList<>();
        private int line;

        ImportCallVisitor(String className, String methodName, List<Violation> violations) {
            super(SpringAsmInfo.ASM_VERSION);
            this.className = className;
            this.methodName = methodName;
            this.violations = violations;
        }

        @Override
        public void visitLineNumber(int lineNumber, Label start) {
            this.line = lineNumber;
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof String || value instanceof Type) {
                pending.add(value);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            // 自前のクラスの定数だけを解決する（ArchUnit の enum 等の定数を誤って拾わない）
            if (opcode == Opcodes.GETSTATIC && owner.startsWith("com/mannschaft/")) {
                pending.addAll(StaticConstants.of(owner).getOrDefault(name, List.of()));
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                boolean isInterface) {
            if (!IMPORTER_OWNER.equals(owner)) {
                return;
            }
            if (LOCATION_IMPORTS.contains(name)) {
                violations.add(new Violation(Kind.WHOLE_PRODUCTION_IMPORT, className, methodName, line,
                        "ClassFileImporter." + name + " による場所単位の一括取り込み"));
            } else if (PACKAGE_IMPORTS.contains(name)) {
                pending.stream()
                        .map(ProductionClassImportGuardTest::packageOf)
                        .filter(ProductionClassImportGuardTest::coversProductionRoot)
                        .findFirst()
                        .ifPresent(pkg -> violations.add(new Violation(Kind.WHOLE_PRODUCTION_IMPORT,
                                className, methodName, line,
                                "ClassFileImporter." + name + "(\"" + pkg + "\") で本番全体を取り込んでいる")));
            }
            if (name.startsWith("import")) {
                pending.clear();
            }
        }
    }

    /** LDC 定数を「取り込み対象のパッケージ名」に読み替える（Type はそのクラスのパッケージ）。 */
    private static String packageOf(Object constant) {
        if (constant instanceof Type type) {
            String name = type.getClassName();
            int lastDot = name.lastIndexOf('.');
            return lastDot < 0 ? "" : name.substring(0, lastDot);
        }
        return (String) constant;
    }

    /** そのパッケージを取り込むと本番ルート全体を含むか（本番ルート自身かその祖先）。 */
    private static boolean coversProductionRoot(String pkg) {
        return pkg.isEmpty() || PRODUCTION_ROOT.equals(pkg) || PRODUCTION_ROOT.startsWith(pkg + ".");
    }

    /** static フィールドの {@code <clinit>} 初期化定数（定数配列を含む）を、クラス単位でキャッシュする。 */
    private static final class StaticConstants {

        private static final Map<String, Map<String, List<Object>>> CACHE = new HashMap<>();

        static synchronized Map<String, List<Object>> of(String ownerInternalName) {
            return CACHE.computeIfAbsent(ownerInternalName, StaticConstants::parse);
        }

        private static Map<String, List<Object>> parse(String ownerInternalName) {
            byte[] bytes = readResource(ownerInternalName + ".class");
            if (bytes == null) {
                return Map.of();
            }
            Map<String, List<Object>> constants = new HashMap<>();
            new ClassReader(bytes).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (!"<clinit>".equals(name)) {
                        return null;
                    }
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        private final List<Object> buffer = new ArrayList<>();

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String || value instanceof Type) {
                                buffer.add(value);
                            }
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                            if (opcode == Opcodes.PUTSTATIC && owner.equals(ownerInternalName)) {
                                constants.put(field, List.copyOf(buffer));
                                buffer.clear();
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
            return constants;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 入出力
    // ══════════════════════════════════════════════════════════════

    /** このクラスが置かれたテスト出力ディレクトリ（Gradle の build/classes/java/test）。 */
    private static Path testClassesRoot() {
        try {
            Path root = Path.of(ProductionClassImportGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertThat(root).as("テスト出力はディレクトリであること").isDirectory();
            return root;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("テスト出力の位置を解決できない", e);
        }
    }

    private static List<Path> classFilesUnder(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readClass(String binaryName) {
        byte[] bytes = readResource(binaryName.replace('.', '/') + ".class");
        assertThat(bytes).as("クラス %s のバイトコードが読めること", binaryName).isNotNull();
        return bytes;
    }

    private static byte[] readResource(String resourceName) {
        try (InputStream in = ProductionClassImportGuardTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
