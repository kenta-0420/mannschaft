package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.Test;
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
    void 凍結行が一行減ったら拒否する() throws IOException {
        writeFixture(SEVEN);
        Files.write(storeDir.resolve("service-api"), List.of(), StandardCharsets.UTF_8);
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir, SEVEN));
    }

    @Test
    void 同文言の二行は一件にまとめず二件として検査する() throws IOException {
        List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> duplicateLines =
            List.of(expectation("重複行のルール", "duplicate-lines", 2));
        writeFixture(duplicateLines);
        assertDoesNotThrow(() -> verifyStoreCounts(storeDir, duplicateLines));
        assertThrows(AssertionError.class, () -> verifyStoreCounts(storeDir,
            List.of(expectation("重複行のルール", "duplicate-lines", 1))));
    }

    private void writeFixture(List<ArchUnitFreezeStoreIntegrityTest.FrozenStoreExpectation> expectations)
            throws IOException {
        writeRules(expectations);
        for (var expectation : expectations) {
            Files.write(storeDir.resolve(expectation.storeFileName()),
                java.util.Collections.nCopies(expectation.expectedLineCount(), "日本語の違反"), StandardCharsets.UTF_8);
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
