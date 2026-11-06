package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static com.mannschaft.app.common.architecture.ArchUnitFreezeStoreIntegrityTest.verifyStoreCounts;
import static com.mannschaft.app.common.architecture.ArchUnitFreezeStoreIntegrityTest.verifyStoreMapping;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 正本を書き換えず、凍結番人自身の検出漏れと境界を実ファイルで検証する。 */
@Tag(ArchUnitTestTag.ARCHUNIT)
class ArchUnitFreezeStoreIntegritySelfTest {
    private static final String SERVICE_API = "service API must not expose entities in signature (D-1 API boundary)";
    private static final List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> SEVEN = List.of(
        expectation("public controller endpoints must have an authorization signal (Wave4)", "authz", 0),
        expectation("no cross-domain entity dependency (D-1)", "entity", 1),
        expectation("transactional should not span other-domain repositories (D-3)", "tx", 1),
        expectation("transactional entry should not transitively reach other-domain repositories (D-3T)", "transitive", 1),
        expectation("entities should extend UuidV7Entity (D-2b)", "uuid", 1),
        expectation("no cross-domain repository dependency (D-5)", "repository", 1),
        expectation(SERVICE_API, "service-api", 1));

    @TempDir
    Path storeDir;

    @Test
    void 正本の期待登録はServiceAPIを含む七ルールである() {
        Set<String> actual = ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS.stream()
            .map(ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation::ruleDescription)
            .collect(Collectors.toSet());
        Set<String> required = SEVEN.stream()
            .map(ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation::ruleDescription)
            .collect(Collectors.toSet());
        assertEquals(required, actual);
    }

    @Test
    void 七登録と空Authzおよび日本語の正常ストアは通る() throws IOException {
        writeFixture(SEVEN);
        assertDoesNotThrow(() -> verifyStoreMapping(storeDir, SEVEN));
        assertDoesNotThrow(() -> verifyStoreCounts(storeDir, SEVEN));
    }

    @Test
    void ServiceAPI登録がstoredRulesから欠落したら拒否する() throws IOException {
        writeFixture(SEVEN);
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> missing = SEVEN.subList(0, 6);
        writeRules(missing);
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    @Test
    void 期待側の登録が欠落してもstoredRulesに残るルールを拒否する() throws IOException {
        writeFixture(SEVEN);
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN.subList(0, 6)));
    }

    @Test
    void 未知の八番目ルールを拒否する() throws IOException {
        writeFixture(SEVEN);
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> extra = new ArrayList<>(SEVEN);
        extra.add(expectation("未承認のルール", "unknown", 0));
        writeRules(extra);
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    @Test
    void UUIDの取り違えを拒否する() throws IOException {
        writeFixture(SEVEN);
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> wrong = new ArrayList<>(SEVEN);
        wrong.set(6, expectation(SERVICE_API, "wrong-uuid", 1));
        writeRules(wrong);
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    @Test
    void 別ルールに同じUUIDを期待しても拒否する() throws IOException {
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> duplicate = List.of(
            expectation("ルール一", "same-uuid", 1), expectation("ルール二", "same-uuid", 1));
        writeFixture(duplicate);
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, duplicate));
    }

    @Test
    void storedRulesの欠損を拒否する() throws IOException {
        writeFixture(SEVEN);
        Files.delete(storeDir.resolve("stored.rules"));
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    @Test
    void ServiceAPI実ストアの欠損を拒否する() throws IOException {
        writeFixture(SEVEN);
        Files.delete(storeDir.resolve("service-api"));
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, SEVEN));
    }

    @Test
    void 凍結行が一行増えたら拒否する() throws IOException {
        writeFixture(SEVEN);
        Files.write(storeDir.resolve("service-api"), List.of("元の違反", "新しい違反"), StandardCharsets.UTF_8);
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, SEVEN));
    }

    @Test
    void 凍結行が一行減っても上限未満なので通る() throws IOException {
        writeFixture(SEVEN);
        Files.write(storeDir.resolve("service-api"), List.of(), StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> verifyStoreCounts(storeDir, SEVEN));
    }

    @Test
    void 同文言の二行は上限内でも拒否する() throws IOException {
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> duplicateLines =
            List.of(expectation(SERVICE_API, "duplicate-lines", 2));
        writeFixture(duplicateLines);
        Files.write(storeDir.resolve("duplicate-lines"),
            java.util.Collections.nCopies(2, fixtureLines(SERVICE_API, 1).getFirst()));
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, duplicateLines));
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir,
            List.of(expectation(SERVICE_API, "duplicate-lines", 1))));
    }

    @Test
    void 全七ストアは上限ちょうどと一件未満と空で通り一件超過を拒否する() throws IOException {
        for (var e : ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS) {
            var limit = expectation(e.ruleDescription(), e.storeFileName(), 2);
            for (int count : List.of(2, 1, 0)) {
                Files.write(storeDir.resolve(e.storeFileName()), fixtureLines(e.ruleDescription(), count));
                assertDoesNotThrow(() -> verifyStoreCounts(storeDir, List.of(limit)), e.ruleDescription());
            }
            Files.write(storeDir.resolve(e.storeFileName()), fixtureLines(e.ruleDescription(), 3));
            assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, List.of(limit)));
            var zero = expectation(e.ruleDescription(), e.storeFileName(), 0);
            Files.write(storeDir.resolve(e.storeFileName()), List.of());
            assertDoesNotThrow(() -> verifyStoreCounts(storeDir, List.of(zero)));
            Files.write(storeDir.resolve(e.storeFileName()), fixtureLines(e.ruleDescription(), 1));
            assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, List.of(zero)));
        }
    }

    @Test
    void 全七形式の重複と逆順と空白行と空行と別形式は拒否する() throws IOException {
        for (var e : ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS) {
            List<String> valid = fixtureLines(e.ruleDescription(), 2);
            int next = (ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS.indexOf(e) + 1)
                % ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS.size();
            String otherRule = ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS.get(next).ruleDescription();
            for (List<String> invalid : List.of(List.of(valid.getFirst(), valid.getFirst()),
                    valid.reversed(), List.of(" "), List.of(""), List.of("別の形式"), fixtureLines(otherRule, 1))) {
                Files.write(storeDir.resolve(e.storeFileName()), invalid);
                var limit = expectation(e.ruleDescription(), e.storeFileName(), invalid.size());
                assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, List.of(limit)),
                    e.ruleDescription() + ": " + invalid);
            }
        }
    }

    @Test
    void 三依存ストアの旧形式と識別suffix欠落は拒否する() throws IOException {
        for (var e : ArchUnitFreezeStoreIntegrityTest.EXPECTATIONS) {
            String line = fixtureLines(e.ruleDescription(), 1).getFirst();
            if (!line.contains(" | dependency: ")) {
                continue;
            }
            for (String invalid : List.of(line.substring(0, line.indexOf(" | dependency: ")),
                    line.replace(" [occurrence=1]", ""))) {
                Files.writeString(storeDir.resolve(e.storeFileName()), invalid + "\n");
                var one = expectation(e.ruleDescription(), e.storeFileName(), 1);
                assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, List.of(one)));
            }
        }
    }

    @Test
    void storedRulesの同じキー二重登録は同じ値でも拒否する() throws IOException {
        writeFixture(SEVEN);
        Properties duplicate = new Properties();
        duplicate.setProperty(SERVICE_API, "service-api");
        try (OutputStream out = Files.newOutputStream(storeDir.resolve("stored.rules"),
                java.nio.file.StandardOpenOption.APPEND)) {
            duplicate.store(out, "二重登録");
        }
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    @Test
    void 余分な実ファイルを拒否する() throws IOException {
        writeFixture(SEVEN);
        Files.writeString(storeDir.resolve("unexpected-store"), "");
        assertThrows(AssertionError.class, () -> verifyStoreMapping(storeDir, SEVEN));
    }

    private static List<String> fixtureLines(String rule, int count) {
        List<String> result = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String source = "com.mannschaft.app.alpha.Service" + i;
            String repo = "com.mannschaft.app.beta.repository.ExampleRepository";
            String entity = "com.mannschaft.app.beta.entity.ExampleEntity";
            String dependency = " | dependency: Field <" + source + ".日本語> has type <";
            String line;
            if (rule.contains("(Wave4)")) {
                line = source + ".read() is a public Controller Mapping endpoint without an authorization signal "
                    + "(@PreAuthorize / AccessControlService / ContentVisibilityChecker / *AccessGuard / *AccessService call) at (Service.java:1)";
            } else if (rule.endsWith("(D-1)")) {
                line = source + " (domain 'alpha') depends on other-domain entity " + entity
                    + " (domain 'beta')" + dependency + entity + "> in (Service.java:1) [occurrence=1]";
            } else if (rule.endsWith("(D-3)")) {
                line = source + " (domain 'alpha', @Transactional) depends on other-domain repository " + repo
                    + " (domain 'beta')" + dependency + repo + "> in (Service.java:1) [occurrence=1]";
            } else if (rule.endsWith("(D-5)")) {
                line = source + " (domain 'alpha') depends on other-domain repository " + repo
                    + " (domain 'beta')" + dependency + repo + "> in (Service.java:1) [occurrence=1]";
            } else if (rule.endsWith("(D-3T)")) {
                line = "@Transactional entry " + source + ".read() (domain 'alpha') reaches other-domain repository "
                    + repo + " (domain 'beta') [D-3T]";
            } else if (rule.endsWith("(D-2b)")) {
                line = "Class <" + source + "> is not assignable to com.mannschaft.app.common.entity.UuidV7Entity in (Service.java:0)";
            } else if (rule.equals(SERVICE_API)) {
                line = source + ".read() exposes @Entity class " + entity + " in its signature at (Service.java:1)";
            } else {
                throw new AssertionError("未知の検体ルール: " + rule);
            }
            result.add(line);
        }
        return result.stream().sorted().toList();
    }

    private void writeFixture(List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> expectations)
            throws IOException {
        writeRules(expectations);
        for (var expectation : expectations) {
            Files.write(storeDir.resolve(expectation.storeFileName()),
                !SEVEN.stream().anyMatch(e -> e.ruleDescription().equals(expectation.ruleDescription()))
                    ? java.util.Collections.nCopies(expectation.expectedLineCount(), "日本語の違反")
                    : fixtureLines(expectation.ruleDescription(), expectation.expectedLineCount()), StandardCharsets.UTF_8);
        }
    }

    private void writeRules(List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> expectations)
            throws IOException {
        Properties rules = new Properties();
        for (var expectation : expectations) {
            rules.setProperty(expectation.ruleDescription(), expectation.storeFileName());
        }
        try (OutputStream out = Files.newOutputStream(storeDir.resolve("stored.rules"))) {
            rules.store(out, "所有一時ストア");
        }
    }

    private static ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation expectation(
            String description, String file, int count) {
        return new ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation(description, file, count);
    }
}
