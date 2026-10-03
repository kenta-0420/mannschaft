package com.mannschaft.app.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;

/**
 * 番人: ArchUnit を使うテストは タグ "archunit" を持ち、Spring を使わない（CMP-261002-1606）。
 *
 * <p>ArchUnit の本番取り込みは 1 JVM あたり約 1.1GB を占めるため、通常の {@code test} タスクは
 * タグ {@value ArchUnitTestTag#ARCHUNIT} を除外し、専用タスク {@code archTest} が別 JVM で走らせる
 * （{@code backend/build.gradle.kts}）。この分離はタグの付け忘れ 1 件で崩れる（付け忘れたテストは
 * Spring 系 IT と同じ JVM に戻り、OOM の原因を再び持ち込む）ため、次を機械的に強制する。
 * <ul>
 *   <li>(R1) ArchUnit（{@code com.tngtech.archunit.*}）または共有ホルダ {@code ProductionClasses} を
 *       参照するテストクラスは、タグを持つ。参照はテストツリー内のヘルパを<b>推移的</b>にたどって判定する
 *       （ヘルパ経由で取り込むテストも対象）。Jupiter のテストメソッドを持つクラスは
 *       {@code @Tag("archunit")}、{@code @AnalyzeClasses} のクラスは {@code @ArchTag("archunit")}
 *       （ArchUnit エンジンは Jupiter の {@code @Tag} を読まない）を要する。</li>
 *   <li>(R2) タグを持つテストクラスは Spring のテストコンテキスト（{@code @SpringBootTest}・
 *       {@code @WebMvcTest}・{@code @DataJpaTest}・{@code @ContextConfiguration}・
 *       {@code AbstractMySqlIntegrationTest} の継承 等）を使わない。archTest は Spring を積まない前提の
 *       JVM であり、混ぜると今度は archTest 側が膨らむ。</li>
 *   <li>(R3) タグを持つテストクラスは shard 重み表（{@code shard-weights.properties}）に載らない。
 *       載ると通常 {@code test} の shard 貪欲割当が、実際には走らない秒数を数えて偏る。</li>
 * </ul>
 *
 * <p><b>検出方式</b>: テスト出力（{@code build/classes/java/test}）の .class を Spring 同梱の ASM と
 * 定数プールの直接解析で 1 つずつ読む（{@link ProductionClassImportGuardTest} と同じ流儀。ArchUnit で
 * テストツリー全体を取り込むと番人自身がメモリを食うため使わない）。参照は CONSTANT_Class・
 * メンバ参照の記述子・宣言（フィールド/メソッド/シグネチャ/アノテーション）から集め、文字列定数は数えない。
 *
 * <p>この番人自身はタグを持たず通常の {@code test} で走る（archTest の配線が壊れても検出が止まらないように）。
 * 凍結ストア・許可リストは使わない。
 */
@DisplayName("番人: ArchUnit を使うテストはタグ archunit を持ち Spring を使わない（CMP-261002-1606）")
class ArchUnitTestTagGuardTest {

    private static final String ARCHUNIT_PREFIX = "com/tngtech/archunit/";
    /** 共有ホルダ（クラスリテラルで参照するとこの番人自身が R1 対象になるため文字列で持つ）。 */
    private static final String HOLDER = "com/mannschaft/app/common/architecture/ProductionClasses";

    private static final String TAG_DESC = "Lorg/junit/jupiter/api/Tag;";
    private static final String TAGS_DESC = "Lorg/junit/jupiter/api/Tags;";
    private static final String ARCH_TAG_DESC = "Lcom/tngtech/archunit/junit/ArchTag;";
    private static final String ARCH_TAGS_DESC = "Lcom/tngtech/archunit/junit/ArchTags;";
    private static final String ANALYZE_CLASSES_DESC = "Lcom/tngtech/archunit/junit/AnalyzeClasses;";
    private static final String ARCH_TEST_DESC = "Lcom/tngtech/archunit/junit/ArchTest;";
    private static final Set<String> JUPITER_TEST_DESCS = Set.of(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/params/ParameterizedTest;");
    /** Spring のテストコンテキストを起動・構成する型の接頭辞。 */
    private static final List<String> SPRING_CONTEXT_PREFIXES = List.of(
            "org/springframework/boot/test/context/",
            "org/springframework/boot/test/autoconfigure/",
            "org/springframework/test/context/");

    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([\\w$/]+)[;<]");
    private static final Pattern INTERNAL_NAME = Pattern.compile("[\\w$]+(/[\\w$]+)+");

    /** 違反の種別。 */
    enum Kind {
        MISSING_TAG("(R1) ArchUnit を使うのにタグ archunit が無い"),
        TAGGED_USES_SPRING("(R2) タグ archunit のテストが Spring を使う"),
        TAGGED_IN_SHARD_WEIGHTS("(R3) タグ archunit のテストが shard 重み表に載っている");

        private final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 1件の違反。 */
    record Violation(Kind kind, String className, String detail) {
        String describe() {
            return kind.label + ": " + className + " — " + detail;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 本番（テストツリー全体）の判定
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("R1〜R3: テストツリー全体に違反が無い")
    void テストツリー全体に違反が無い() {
        Map<String, byte[]> classes = testTreeClasses();
        assertThat(classes).as("テスト出力の .class が少なすぎる（走査が空振りしていないか）").hasSizeGreaterThan(1000);

        Analysis analysis = Analysis.of(classes);
        List<Violation> violations = analysis.violations(shardWeightClasses());

        assertThat(violations)
                .as(() -> "ArchUnit を使うテストは @Tag/@ArchTag(ArchUnitTestTag.ARCHUNIT) を付け、Spring を使わず、"
                        + "shard 重み表に載せないこと（CMP-261002-1606・TEST_CONVENTION.md）。違反 "
                        + violations.size() + " 件:\n" + violations.stream()
                                .sorted(Comparator.comparing(Violation::className))
                                .map(Violation::describe)
                                .collect(Collectors.joining("\n")))
                .isEmpty();
    }

    @Test
    @DisplayName("空振り防止: 実在の ArchUnit テストを使用・タグ付きと判定し、実在の Spring IT を Spring 使用と判定する")
    void 実在クラスで判定が空振りしていない() {
        Analysis analysis = Analysis.of(testTreeClasses());
        String archJupiter = "com/mannschaft/app/common/architecture/ShiftTxFacadeArchTest";
        String archEngine = "com/mannschaft/app/common/architecture/CrossDomainRepositoryDependencyArchTest";
        String springIt = "com/mannschaft/app/MannschaftApplicationTests";

        assertThat(analysis.usesArchUnit(archJupiter)).as(archJupiter + " は ArchUnit を使う").isTrue();
        assertThat(analysis.hasJupiterTag(archJupiter)).as(archJupiter + " は @Tag(archunit) を持つ").isTrue();
        assertThat(analysis.usesArchUnit(archEngine)).as(archEngine + " は ArchUnit を使う").isTrue();
        assertThat(analysis.hasArchTag(archEngine)).as(archEngine + " は @ArchTag(archunit) を持つ").isTrue();
        assertThat(analysis.usesSpring(springIt)).as(springIt + " は Spring を使う（継承経由）").isTrue();
        assertThat(analysis.usesArchUnit(springIt)).as(springIt + " は ArchUnit を使わない").isFalse();
        assertThat(analysis.taggedTestGroups())
                .as("タグ付きテストクラスが一定数ある（archTest が空にならない）")
                .hasSizeGreaterThan(40);
    }

    // ══════════════════════════════════════════════════════════════
    // 自己検証（ASM で生成した見本で、検出すべきものを検出し、許可形を検出しない）
    // ══════════════════════════════════════════════════════════════

    private static final String SAMPLE = "sample/archtag/";
    private static final String JAVA_CLASSES = "Lcom/tngtech/archunit/core/domain/JavaClasses;";

    @Test
    @DisplayName("自己検証 R1: ArchUnit を参照する Jupiter テストにタグが無ければ検出する")
    void 自己検証_タグ無しJupiterを検出する() {
        Map<String, byte[]> classes = Map.of(
                SAMPLE + "Untagged", sample(SAMPLE + "Untagged", null, cv -> {
                    archUnitField(cv);
                    jupiterTestMethod(cv);
                }));
        assertThat(Analysis.of(classes).violations(Set.of()))
                .extracting(Violation::kind, Violation::className)
                .containsExactly(tuple(Kind.MISSING_TAG, SAMPLE + "Untagged"));
    }

    @Test
    @DisplayName("自己検証 R1: @Tag(archunit) を付けた Jupiter テストは違反にならない（@Tags の中でも可）")
    void 自己検証_タグ付きJupiterは許可する() {
        Map<String, byte[]> classes = Map.of(
                SAMPLE + "Tagged", sample(SAMPLE + "Tagged", null, cv -> {
                    tag(cv, TAG_DESC);
                    archUnitField(cv);
                    jupiterTestMethod(cv);
                }),
                SAMPLE + "TaggedInContainer", sample(SAMPLE + "TaggedInContainer", null, cv -> {
                    AnnotationVisitor tags = cv.visitAnnotation(TAGS_DESC, true);
                    AnnotationVisitor array = tags.visitArray("value");
                    AnnotationVisitor inner = array.visitAnnotation(null, TAG_DESC);
                    inner.visit("value", ArchUnitTestTag.ARCHUNIT);
                    inner.visitEnd();
                    array.visitEnd();
                    tags.visitEnd();
                    archUnitField(cv);
                    jupiterTestMethod(cv);
                }));
        assertThat(Analysis.of(classes).violations(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("自己検証 R1: @AnalyzeClasses のクラスは @ArchTag が要る（Jupiter の @Tag だけでは検出する）")
    void 自己検証_AnalyzeClassesはArchTagを要する() {
        Map<String, byte[]> classes = Map.of(
                SAMPLE + "EngineJupiterTagOnly", sample(SAMPLE + "EngineJupiterTagOnly", null, cv -> {
                    cv.visitAnnotation(ANALYZE_CLASSES_DESC, true).visitEnd();
                    tag(cv, TAG_DESC);
                }),
                SAMPLE + "EngineArchTagged", sample(SAMPLE + "EngineArchTagged", null, cv -> {
                    cv.visitAnnotation(ANALYZE_CLASSES_DESC, true).visitEnd();
                    tag(cv, ARCH_TAG_DESC);
                }));
        assertThat(Analysis.of(classes).violations(Set.of()))
                .extracting(Violation::kind, Violation::className)
                .containsExactly(tuple(Kind.MISSING_TAG, SAMPLE + "EngineJupiterTagOnly"));
    }

    @Test
    @DisplayName("自己検証 R1: ArchUnit を使うヘルパを経由するテスト（推移・ホルダ参照・入れ子クラス）も検出する")
    void 自己検証_推移的な使用を検出する() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(SAMPLE + "Helper", sample(SAMPLE + "Helper", null, ArchUnitTestTagGuardTest::archUnitField));
        classes.put(SAMPLE + "Middle", sample(SAMPLE + "Middle", null, cv -> callStatic(cv, SAMPLE + "Helper")));
        classes.put(SAMPLE + "ViaHelper", sample(SAMPLE + "ViaHelper", null, cv -> {
            jupiterTestMethod(cv);
            callStatic(cv, SAMPLE + "Middle");
        }));
        classes.put(SAMPLE + "ViaHolder", sample(SAMPLE + "ViaHolder", null, cv -> {
            jupiterTestMethod(cv);
            callStatic(cv, HOLDER);
        }));
        // 入れ子クラスだけが ArchUnit を参照し、テストメソッドも入れ子（@Nested 相当）にある
        classes.put(SAMPLE + "Outer", sample(SAMPLE + "Outer", null, cv -> { }));
        classes.put(SAMPLE + "Outer$Inner", sample(SAMPLE + "Outer$Inner", null, cv -> {
            archUnitField(cv);
            jupiterTestMethod(cv);
        }));
        assertThat(Analysis.of(classes).violations(Set.of()))
                .extracting(Violation::kind, Violation::className)
                .containsExactlyInAnyOrder(
                        tuple(Kind.MISSING_TAG, SAMPLE + "ViaHelper"),
                        tuple(Kind.MISSING_TAG, SAMPLE + "ViaHolder"),
                        tuple(Kind.MISSING_TAG, SAMPLE + "Outer"));
    }

    @Test
    @DisplayName("自己検証 R1: ArchUnit を参照しないテスト・文字列定数で名前を持つだけのテストは対象外")
    void 自己検証_無関係なテストは対象外() {
        Map<String, byte[]> classes = Map.of(
                SAMPLE + "Plain", sample(SAMPLE + "Plain", null, cv -> {
                    jupiterTestMethod(cv);
                    MethodVisitor mv = cv.visitMethod(Opcodes.ACC_STATIC, "s", "()V", null, null);
                    mv.visitCode();
                    mv.visitLdcInsn("com/tngtech/archunit/core/domain/JavaClasses");
                    mv.visitInsn(Opcodes.POP);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(0, 0);
                    mv.visitEnd();
                }));
        Analysis analysis = Analysis.of(classes);
        assertThat(analysis.usesArchUnit(SAMPLE + "Plain")).isFalse();
        assertThat(analysis.violations(Set.of())).isEmpty();
    }

    @Test
    @DisplayName("自己検証 R2: タグ付きテストが Spring を直接・継承経由で使えば検出する")
    void 自己検証_タグ付きのSpring使用を検出する() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(SAMPLE + "Direct", sample(SAMPLE + "Direct", null, cv -> {
            tag(cv, TAG_DESC);
            cv.visitAnnotation("Lorg/springframework/boot/test/context/SpringBootTest;", true).visitEnd();
            jupiterTestMethod(cv);
        }));
        classes.put(SAMPLE + "Base", sample(SAMPLE + "Base", null, cv ->
                cv.visitAnnotation("Lorg/springframework/test/context/ContextConfiguration;", true).visitEnd()));
        classes.put(SAMPLE + "Inherited", sample(SAMPLE + "Inherited", SAMPLE + "Base", cv -> {
            tag(cv, TAG_DESC);
            archUnitField(cv);
            jupiterTestMethod(cv);
        }));
        assertThat(Analysis.of(classes).violations(Set.of()))
                .extracting(Violation::kind, Violation::className)
                .containsExactlyInAnyOrder(
                        tuple(Kind.TAGGED_USES_SPRING, SAMPLE + "Direct"),
                        tuple(Kind.TAGGED_USES_SPRING, SAMPLE + "Inherited"));
    }

    @Test
    @DisplayName("自己検証 R3: タグ付きテストが shard 重み表に載っていれば検出する")
    void 自己検証_重み表への混入を検出する() {
        Map<String, byte[]> classes = Map.of(
                SAMPLE + "Weighted", sample(SAMPLE + "Weighted", null, cv -> {
                    tag(cv, TAG_DESC);
                    archUnitField(cv);
                    jupiterTestMethod(cv);
                }));
        assertThat(Analysis.of(classes).violations(Set.of("sample.archtag.Weighted")))
                .extracting(Violation::kind, Violation::className)
                .containsExactly(tuple(Kind.TAGGED_IN_SHARD_WEIGHTS, SAMPLE + "Weighted"));
    }

    // ══════════════════════════════════════════════════════════════
    // 判定ロジック
    // ══════════════════════════════════════════════════════════════

    /** 1 .class から読み取った事実。 */
    static final class ClassFacts {
        final String name;
        String superName;
        final Set<String> refs = new HashSet<>();
        final Set<String> classTags = new HashSet<>();
        final Set<String> classArchTags = new HashSet<>();
        boolean analyzeClasses;
        boolean jupiterTests;

        ClassFacts(String name) {
            this.name = name;
        }
    }

    /** テストツリー全体の解析結果（トップレベルクラス単位にまとめる）。 */
    static final class Analysis {
        private final Map<String, ClassFacts> facts;
        private final Map<String, List<ClassFacts>> groups;
        private final Set<String> archUnitGroups;

        private Analysis(Map<String, ClassFacts> facts) {
            this.facts = facts;
            this.groups = facts.values().stream()
                    .collect(Collectors.groupingBy(f -> topLevel(f.name), LinkedHashMap::new, Collectors.toList()));
            this.archUnitGroups = computeArchUnitGroups();
        }

        static Analysis of(Map<String, byte[]> classBytes) {
            Map<String, ClassFacts> facts = new HashMap<>();
            classBytes.forEach((name, bytes) -> {
                ClassFacts f = read(bytes);
                facts.put(f.name, f);
            });
            return new Analysis(facts);
        }

        /** ArchUnit/ホルダを直接参照するグループから、参照を逆にたどって推移的に広げる。 */
        private Set<String> computeArchUnitGroups() {
            Map<String, Set<String>> groupRefs = new HashMap<>();
            Set<String> result = new HashSet<>();
            groups.forEach((group, members) -> {
                Set<String> refGroups = new HashSet<>();
                for (ClassFacts f : members) {
                    for (String ref : f.refs) {
                        if (ref.startsWith(ARCHUNIT_PREFIX) || ref.equals(HOLDER)) {
                            result.add(group);
                        }
                        String refGroup = topLevel(ref);
                        if (!refGroup.equals(group) && groups.containsKey(refGroup)) {
                            refGroups.add(refGroup);
                        }
                    }
                }
                groupRefs.put(group, refGroups);
            });
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Map.Entry<String, Set<String>> e : groupRefs.entrySet()) {
                    if (!result.contains(e.getKey()) && e.getValue().stream().anyMatch(result::contains)) {
                        result.add(e.getKey());
                        changed = true;
                    }
                }
            }
            return result;
        }

        boolean usesArchUnit(String group) {
            return archUnitGroups.contains(group);
        }

        boolean usesSpring(String group) {
            List<ClassFacts> members = groups.getOrDefault(group, List.of());
            if (members.stream().anyMatch(f -> f.refs.stream().anyMatch(ArchUnitTestTagGuardTest::isSpringContext))) {
                return true;
            }
            ClassFacts top = facts.get(group);
            Set<String> seen = new HashSet<>();
            while (top != null && top.superName != null && seen.add(top.superName)) {
                if (!facts.containsKey(top.superName)) {
                    return false;
                }
                String superGroup = topLevel(top.superName);
                if (groups.getOrDefault(superGroup, List.of()).stream()
                        .anyMatch(f -> f.refs.stream().anyMatch(ArchUnitTestTagGuardTest::isSpringContext))) {
                    return true;
                }
                top = facts.get(top.superName);
            }
            return false;
        }

        /** Jupiter の @Tag は @Inherited のため、テストツリー内の親クラスの付与も数える。 */
        boolean hasJupiterTag(String group) {
            ClassFacts f = facts.get(group);
            Set<String> seen = new HashSet<>();
            while (f != null && seen.add(f.name)) {
                if (f.classTags.contains(ArchUnitTestTag.ARCHUNIT)) {
                    return true;
                }
                f = f.superName == null ? null : facts.get(f.superName);
            }
            return false;
        }

        boolean hasArchTag(String group) {
            ClassFacts f = facts.get(group);
            return f != null && f.classArchTags.contains(ArchUnitTestTag.ARCHUNIT);
        }

        private boolean isTestGroup(String group) {
            return needsJupiterTag(group) || needsArchTag(group);
        }

        private boolean needsJupiterTag(String group) {
            return groups.getOrDefault(group, List.of()).stream().anyMatch(f -> f.jupiterTests);
        }

        private boolean needsArchTag(String group) {
            ClassFacts top = facts.get(group);
            return top != null && top.analyzeClasses;
        }

        Set<String> taggedTestGroups() {
            return groups.keySet().stream()
                    .filter(this::isTestGroup)
                    .filter(g -> hasJupiterTag(g) || hasArchTag(g))
                    .collect(Collectors.toSet());
        }

        List<Violation> violations(Set<String> shardWeightClasses) {
            List<Violation> violations = new ArrayList<>();
            for (String group : groups.keySet()) {
                if (!isTestGroup(group)) {
                    continue;
                }
                if (usesArchUnit(group)) {
                    List<String> missing = new ArrayList<>();
                    if (needsJupiterTag(group) && !hasJupiterTag(group)) {
                        missing.add("@Tag(ArchUnitTestTag.ARCHUNIT)");
                    }
                    if (needsArchTag(group) && !hasArchTag(group)) {
                        missing.add("@ArchTag(ArchUnitTestTag.ARCHUNIT)");
                    }
                    if (!missing.isEmpty()) {
                        violations.add(new Violation(Kind.MISSING_TAG, group, String.join(" と ", missing) + " を付けよ"));
                    }
                }
                boolean tagged = hasJupiterTag(group) || hasArchTag(group);
                if (tagged && usesSpring(group)) {
                    violations.add(new Violation(Kind.TAGGED_USES_SPRING, group,
                            "Spring のテストコンテキストを使うテストを archTest（Spring を積まない別 JVM）に入れない"));
                }
                if (tagged && shardWeightClasses.contains(group.replace('/', '.'))) {
                    violations.add(new Violation(Kind.TAGGED_IN_SHARD_WEIGHTS, group,
                            "shard-weights.properties から該当行を削除せよ（通常 test では走らない）"));
                }
            }
            return violations;
        }
    }

    private static boolean isSpringContext(String internalName) {
        return SPRING_CONTEXT_PREFIXES.stream().anyMatch(internalName::startsWith);
    }

    private static String topLevel(String internalName) {
        int dollar = internalName.indexOf('$');
        return dollar < 0 ? internalName : internalName.substring(0, dollar);
    }

    /** 1 .class を読む。参照は定数プール（クラス・メンバ記述子）と宣言（ASM）の両方から集める。 */
    static ClassFacts read(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassFacts f = new ClassFacts(reader.getClassName());
        constantPoolReferences(bytes, f.refs);
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                f.superName = superName;
                addDescriptor(signature, f.refs);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                addDescriptor(descriptor, f.refs);
                if (ANALYZE_CLASSES_DESC.equals(descriptor)) {
                    f.analyzeClasses = true;
                }
                return tagCollector(descriptor, f);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value) {
                addDescriptor(descriptor, f.refs);
                addDescriptor(signature, f.refs);
                return new FieldVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        addDescriptor(desc, f.refs);
                        if (ARCH_TEST_DESC.equals(desc)) {
                            f.refs.add(ARCHUNIT_PREFIX + "junit/ArchTest");
                        }
                        return null;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                addDescriptor(descriptor, f.refs);
                addDescriptor(signature, f.refs);
                return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        addDescriptor(desc, f.refs);
                        if (JUPITER_TEST_DESCS.contains(desc)) {
                            f.jupiterTests = true;
                        }
                        return null;
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                        addDescriptor(desc, f.refs);
                        return null;
                    }

                    @Override
                    public void visitLocalVariable(String n, String desc, String sig,
                            org.springframework.asm.Label start, org.springframework.asm.Label end, int index) {
                        addDescriptor(desc, f.refs);
                        addDescriptor(sig, f.refs);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return f;
    }

    /** クラス注釈 @Tag/@Tags/@ArchTag/@ArchTags の値を集める。 */
    private static AnnotationVisitor tagCollector(String descriptor, ClassFacts f) {
        boolean jupiter = TAG_DESC.equals(descriptor) || TAGS_DESC.equals(descriptor);
        boolean arch = ARCH_TAG_DESC.equals(descriptor) || ARCH_TAGS_DESC.equals(descriptor);
        if (!jupiter && !arch) {
            return null;
        }
        Set<String> sink = jupiter ? f.classTags : f.classArchTags;
        return new AnnotationVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String s) {
                    sink.add(s);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                return this;
            }
        };
    }

    /**
     * 定数プールから型参照を集める。CONSTANT_Class の名前と、メンバ参照（NameAndType）・MethodType の
     * 記述子だけを数え、CONSTANT_String（文字列定数）は数えない。
     */
    private static void constantPoolReferences(byte[] bytes, Set<String> out) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.skipNBytes(8);
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            List<Integer> classNameIdx = new ArrayList<>();
            List<Integer> descriptorIdx = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIdx.add(in.readUnsignedShort());
                    case 16 -> descriptorIdx.add(in.readUnsignedShort());
                    case 12 -> {
                        in.readUnsignedShort();
                        descriptorIdx.add(in.readUnsignedShort());
                    }
                    case 8, 19, 20 -> in.skipNBytes(2);
                    case 3, 4, 9, 10, 11, 17, 18 -> in.skipNBytes(4);
                    case 15 -> in.skipNBytes(3);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++;
                    }
                    default -> throw new IllegalStateException("未知の定数プールタグ " + tag);
                }
            }
            for (int idx : classNameIdx) {
                String name = utf8[idx];
                if (name.startsWith("[")) {
                    addDescriptor(name, out);
                } else if (INTERNAL_NAME.matcher(name).matches()) {
                    out.add(name);
                }
            }
            for (int idx : descriptorIdx) {
                addDescriptor(utf8[idx], out);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void addDescriptor(String descriptor, Set<String> out) {
        if (descriptor == null) {
            return;
        }
        Matcher m = DESCRIPTOR_TYPE.matcher(descriptor);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 入出力
    // ══════════════════════════════════════════════════════════════

    private static Map<String, byte[]> testTreeClasses() {
        Path root = testClassesRoot();
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path p : paths.filter(x -> x.toString().endsWith(".class")).sorted().toList()) {
                byte[] bytes = Files.readAllBytes(p);
                result.put(new ClassReader(bytes).getClassName(), bytes);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static Path testClassesRoot() {
        try {
            Path root = Path.of(ArchUnitTestTagGuardTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            assertThat(root).as("テスト出力はディレクトリであること").isDirectory();
            return root;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("テスト出力の位置を解決できない", e);
        }
    }

    private static Set<String> shardWeightClasses() {
        try (InputStream in = ArchUnitTestTagGuardTest.class.getClassLoader()
                .getResourceAsStream("shard-weights.properties")) {
            assertThat(in).as("shard-weights.properties がクラスパスにあること").isNotNull();
            Properties props = new Properties();
            props.load(in);
            assertThat(props).as("shard 重み表が空でないこと（読み込みの空振り防止）").hasSizeGreaterThan(100);
            return props.stringPropertyNames();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 自己検証用の見本生成
    // ══════════════════════════════════════════════════════════════

    private static byte[] sample(String name, String superName, Consumer<ClassVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null,
                superName == null ? "java/lang/Object" : superName, null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void tag(ClassVisitor cv, String descriptor) {
        AnnotationVisitor av = cv.visitAnnotation(descriptor, true);
        av.visit("value", ArchUnitTestTag.ARCHUNIT);
        av.visitEnd();
    }

    private static void archUnitField(ClassVisitor cv) {
        cv.visitField(Opcodes.ACC_PRIVATE, "classes", JAVA_CLASSES, null, null).visitEnd();
    }

    private static void jupiterTestMethod(ClassVisitor cv) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, "test", "()V", null, null);
        mv.visitAnnotation("Lorg/junit/jupiter/api/Test;", true).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void callStatic(ClassVisitor cv, String owner) {
        MethodVisitor mv = cv.visitMethod(Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "get", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
