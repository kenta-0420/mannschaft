package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import com.tngtech.archunit.library.freeze.ViolationStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 実javac検体で依存の多重度を保ち、既定fuzzy照合の整形耐性と増加検知を検証する。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class DependencyFreezeRecordingTest {
    private static final String SOURCE = "com.mannschaft.app.l1fsource.Example";

    @TempDir
    Path temporary;

    @Test
    void 三番人はfieldとconstructorとsignatureと同一行二callを個別記録する() throws Exception {
        JavaClasses fixture = compileFixture("same-line", 2, false, true);
        for (Class<?> guard : guards()) {
            List<String> details = rule(guard).evaluate(fixture).getFailureReport().getDetails();
            assertEquals(6, details.size(), guard.getSimpleName());
            assertTrue(details.stream().noneMatch(line -> line.contains("OwnRepository")), "自ドメインは除外する");
            assertEquals(6, details.stream().distinct().count(), "同じ行の同じcallも別記録である");
            assertTrue(details.stream().allMatch(line -> line.contains(" | dependency: ")
                && line.matches(".* \\[occurrence=[1-9][0-9]*]$")), details.toString());
            assertEquals(2, details.stream().filter(line -> line.contains(" calls method ")).count());
            assertTrue(details.stream().anyMatch(line -> line.contains("Field <")));
            assertTrue(details.stream().anyMatch(line -> line.contains("Constructor <")));
            assertTrue(details.stream().anyMatch(line -> line.contains(" has parameter of type ")));
            assertTrue(details.stream().anyMatch(line -> line.contains(" has return type ")));
        }
    }

    @Test
    void 再importとクラス走査順が変わっても記録multisetは変わらない() throws Exception {
        JavaClasses first = compileFixture("first", 2, false, true);
        JavaClasses second = compileFixture("second", 2, false, true);
        for (Class<?> guard : guards()) {
            List<String> expected = rule(guard).evaluate(first).getFailureReport().getDetails();
            assertEquals(expected.stream().sorted().toList(),
                rule(guard).evaluate(second).getFailureReport().getDetails().stream().sorted().toList());
        }
    }

    @Test
    void 既定freezeは行移動と別行同一行の整形を許し増加を拒否し減少を縮小する() throws Exception {
        JavaClasses sameLine = compileFixture("base", 2, false, true);
        JavaClasses splitLines = compileFixture("moved", 2, true, true);
        JavaClasses increased = compileFixture("plus", 3, false, true);
        JavaClasses decreased = compileFixture("minus", 1, true, true);
        for (Class<?> guard : guards()) {
            TemporaryViolationStore store = new TemporaryViolationStore(temporary.resolve(guard.getSimpleName()));
            FreezingArchRule frozen = FreezingArchRule.freeze(rule(guard)).persistIn(store);
            assertDoesNotThrow(() -> frozen.check(sameLine));
            assertEquals(6, store.getViolations(frozen).size());
            assertDoesNotThrow(() -> frozen.check(splitLines), "行移動と別行化は新違反ではない");
            assertDoesNotThrow(() -> frozen.check(sameLine), "同一行へ戻しても新違反ではない");
            assertThrows(AssertionError.class, () -> frozen.check(increased));
            assertDoesNotThrow(() -> frozen.check(decreased));
            assertEquals(5, store.getViolations(frozen).size(), "一件減少だけをchip-awayする");
        }
    }

    @Test
    void D3の注釈入口と各番人の自ドメイン除外を維持する() throws Exception {
        JavaClasses fixture = compileFixture("no-tx", 2, false, false);
        assertTrue(rule(CrossDomainTransactionalArchTest.class).evaluate(fixture).getFailureReport().getDetails().isEmpty());
        assertEquals(6, rule(CrossDomainEntityImportArchTest.class).evaluate(fixture).getFailureReport().getDetails().size());
        assertEquals(6, rule(CrossDomainRepositoryDependencyArchTest.class).evaluate(fixture).getFailureReport().getDetails().size());
    }

    private static List<Class<?>> guards() {
        return List.of(CrossDomainEntityImportArchTest.class, CrossDomainTransactionalArchTest.class,
            CrossDomainRepositoryDependencyArchTest.class);
    }

    @SuppressWarnings("unchecked")
    private static ArchRule rule(Class<?> guard) throws ReflectiveOperationException {
        String method = guard == CrossDomainEntityImportArchTest.class
            ? "notDependOnOtherDomainEntities" : "notDependOnOtherDomainRepositories";
        var factory = guard.getDeclaredMethod(method);
        factory.setAccessible(true);
        return classes().that().haveFullyQualifiedName(SOURCE)
            .should((ArchCondition<JavaClass>) factory.invoke(null)).as("temporary " + guard.getSimpleName());
    }

    /** 同一FQCNの実クラスを別ディレクトリへコンパイルし、行番号だけ異なる依存を得る。 */
    private JavaClasses compileFixture(String variant, int calls, boolean split, boolean transactional) throws Exception {
        Path output = Files.createDirectories(temporary.resolve(variant));
        String repo = "com.mannschaft.app.l1ftarget.repository.ExampleRepository";
        String entity = "com.mannschaft.app.l1ftarget.entity.ExampleEntity";
        List<Path> sources = new ArrayList<>();
        sources.add(writeSource(output, repo, "package com.mannschaft.app.l1ftarget.repository; "
            + "public class ExampleRepository { public void read() {} }"));
        sources.add(writeSource(output, entity, "package com.mannschaft.app.l1ftarget.entity; "
            + "public class ExampleEntity { public void read() {} }"));
        sources.add(writeSource(output, "com.mannschaft.app.l1fsource.repository.OwnRepository",
            "package com.mannschaft.app.l1fsource.repository; public class OwnRepository {}"));
        String separator = split ? "\n\n" : " ";
        String body = ("repo.read();" + separator + "entity.read();" + separator).repeat(calls);
        sources.add(writeSource(output, SOURCE, "package com.mannschaft.app.l1fsource;\n"
            + "import " + repo + ";\nimport " + entity + ";\n"
            + (transactional ? "@org.springframework.transaction.annotation.Transactional\n" : "")
            + "public class Example {\n"
            + "private ExampleRepository repo; private ExampleEntity entity;\n"
            + "private com.mannschaft.app.l1fsource.repository.OwnRepository own;\n"
            + "public Example(ExampleRepository repo, ExampleEntity entity) { this.repo=repo; this.entity=entity; }\n"
            + "public ExampleRepository signature(ExampleRepository arg) {return arg;}\n"
            + "public ExampleEntity signature(ExampleEntity arg) {return arg;}\n"
            + "public void call() {" + separator + body + "}\n}"));
        String annotationClasspath = Path.of(org.springframework.transaction.annotation.Transactional.class
            .getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        List<String> options = new ArrayList<>(List.of("-g", "-proc:none", "-classpath",
            annotationClasspath, "-d", output.toString()));
        sources.forEach(path -> options.add(path.toString()));
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
        assertEquals(0, exit, "javac検体の準備は成功が必須");
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            List<Class<?>> imported = new ArrayList<>();
            for (String name : List.of(SOURCE, repo, entity, "com.mannschaft.app.l1fsource.repository.OwnRepository")) {
                imported.add(Class.forName(name, false, loader));
            }
            if (split || variant.equals("second")) {
                java.util.Collections.reverse(imported);
            }
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            try {
                // importClassesも名前からresourceを引くため、検体の所有loaderを明示する。
                Thread.currentThread().setContextClassLoader(loader);
                JavaClasses result = new ClassFileImporter().importClasses(imported);
                assertTrue(result.contain(SOURCE), "実javac検体のimport成功が必須");
                return result;
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }
    }

    private static Path writeSource(Path output, String fqcn, String source) throws IOException {
        Path path = output.resolve(fqcn.replace('.', '/') + ".java");
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
        return path;
    }

    /** 初回作成をこの所有一時ファイルだけに許し、実FreezingArchRuleに既定matcherを使わせる。 */
    private static final class TemporaryViolationStore implements ViolationStore {
        private final Path file;

        private TemporaryViolationStore(Path file) {
            this.file = file;
        }

        @Override
        public void initialize(Properties properties) {
            // 初回生成許可はこの一時storeの存在判定だけに閉じる。グローバル設定は変更しない。
        }

        @Override
        public boolean contains(ArchRule rule) {
            return Files.isRegularFile(file);
        }

        @Override
        public void save(ArchRule rule, List<String> violations) {
            try {
                Files.write(file, violations);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public List<String> getViolations(ArchRule rule) {
            try {
                return Files.readAllLines(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }
}
