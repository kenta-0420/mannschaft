package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 凍結ストア七本の登録閉集合・上限・行形式を読み取り専用で検査する。
 *
 * <p>既存違反のchip-awayは許し、上限を超える追加、重複、整列崩れ、形式不正を拒否する。
 * 上限の引下げは締めレーンが原因を検分して行う。過去の更新履歴は
 * {@code docs/architecture/archunit_freeze_history.md} を参照する。
 * ArchUnit本体や依存記録helperを参照しないため、専用guardの二case経路を保つ。
 */
class ArchUnitFreezeStoreIntegrityTest {

    /** 凍結ストアのルートディレクトリ（{@code backend} をカレントディレクトリとして解決）。 */
    private static final Path STORE_DIR =
        Paths.get("src", "test", "resources", "archunit_store");


    // 更新履歴の原文は docs/architecture/archunit_freeze_history.md に移設した。
    private static final int EXPECTED_LINES_AUTHZ_WAVE4 = 0;
    private static final int EXPECTED_LINES_CROSS_DOMAIN_ENTITY_D1 = 2037;
    private static final int EXPECTED_LINES_CROSS_DOMAIN_TX_D3 = 1292;
    private static final int EXPECTED_LINES_CROSS_DOMAIN_TX_D3T = 7325;
    private static final int EXPECTED_LINES_UUID_V7_D2B = 569;
    private static final int EXPECTED_LINES_CROSS_DOMAIN_REPO_D5 = 1850;
    private static final int EXPECTED_LINES_SERVICE_API = 600;

    /** ルール説明（{@code stored.rules} のキー）・ストアファイル名・期待行数の対応表。 */
    static final List<FrozenStoreExpectation> EXPECTATIONS = List.of(
        new FrozenStoreExpectation(
            "public controller endpoints must have an authorization signal (Wave4)",
            "9ed4737d-c74f-4374-923e-4663d3c9e256",
            EXPECTED_LINES_AUTHZ_WAVE4),
        new FrozenStoreExpectation(
            "no cross-domain entity dependency (D-1)",
            "584c3a46-b9c1-4cc2-bf74-e0a18eab1bef",
            EXPECTED_LINES_CROSS_DOMAIN_ENTITY_D1),
        new FrozenStoreExpectation(
            "transactional should not span other-domain repositories (D-3)",
            "f14374b1-655e-4df2-8e82-2d79c8df9174",
            EXPECTED_LINES_CROSS_DOMAIN_TX_D3),
        new FrozenStoreExpectation(
            "transactional entry should not transitively reach other-domain repositories (D-3T)",
            "296295dd-06cf-4f7b-bf82-315ba12ff501",
            EXPECTED_LINES_CROSS_DOMAIN_TX_D3T),
        new FrozenStoreExpectation(
            "entities should extend UuidV7Entity (D-2b)",
            "2c0ba995-682e-4f80-a5a5-f68c835b720d",
            EXPECTED_LINES_UUID_V7_D2B),
        new FrozenStoreExpectation(
            "no cross-domain repository dependency (D-5)",
            "427c445d-37ce-4d6e-b095-a1733efe209f",
            EXPECTED_LINES_CROSS_DOMAIN_REPO_D5),
        new FrozenStoreExpectation(
            "service API must not expose entities in signature (D-1 API boundary)",
            "93124b52-f328-4f09-8c4f-6d022519fae2",
            EXPECTED_LINES_SERVICE_API)
    );

    @Test
    @DisplayName("stored.rulesのルール説明→ストアファイルUUID対応がずれていない（UUID取り違え検知）")
    void ストアUUID対応の裏取り() throws IOException {
        verifyStoreMapping(STORE_DIR, EXPECTATIONS);
    }

    static void verifyStoreMapping(Path storeDir, List<FrozenStoreExpectation> expectations) throws IOException {
        Path storedRulesFile = storeDir.resolve("stored.rules");
        assertTrue(Files.isRegularFile(storedRulesFile),
            "stored.rules が見つからない: " + storedRulesFile.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Properties storedRules = new Properties() {
            @Override
            public synchronized Object put(Object key, Object value) {
                assertTrue(!containsKey(key), "stored.rules のルール説明が二重登録されています: " + key);
                return super.put(key, value);
            }
        };
        try (InputStream in = Files.newInputStream(storedRulesFile)) {
            storedRules.load(in);
        }

        List<String> mismatches = new ArrayList<>();
        Set<String> expectedRules = new HashSet<>();
        Set<String> expectedStoreFiles = new HashSet<>();
        for (FrozenStoreExpectation expectation : expectations) {
            if (!expectedRules.add(expectation.ruleDescription())) {
                mismatches.add("期待側のルール説明が重複しています: " + expectation.ruleDescription());
            }
            if (!expectedStoreFiles.add(expectation.storeFileName())) {
                mismatches.add("期待側のストアUUIDが重複しています: " + expectation.storeFileName());
            }
        }
        Set<String> expectedFiles = new HashSet<>(expectedStoreFiles);
        expectedFiles.add("stored.rules");
        try (var files = Files.list(storeDir)) {
            Set<String> actualFiles = files.map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
            if (!actualFiles.equals(expectedFiles)) {
                mismatches.add("実ファイル集合が期待登録と一致しません: actual=" + actualFiles
                    + ", expected=" + expectedFiles);
            }
        }
        if (!storedRules.stringPropertyNames().equals(expectedRules)) {
            mismatches.add("stored.rules のルール集合が期待登録と一致しません: actual="
                + storedRules.stringPropertyNames() + ", expected=" + expectedRules);
        }
        if (new HashSet<>(storedRules.values()).size() != storedRules.size()) {
            mismatches.add("stored.rules のストアUUIDが重複しています");
        }
        for (FrozenStoreExpectation expectation : expectations) {
            String actualStoreFile = storedRules.getProperty(expectation.ruleDescription());
            if (actualStoreFile == null) {
                mismatches.add(String.format(
                    "ルール説明 \"%s\" が stored.rules に存在しない（ルール名が変更された、"
                        + "または本テストの期待値が古い可能性）",
                    expectation.ruleDescription()));
            } else if (!actualStoreFile.equals(expectation.storeFileName())) {
                mismatches.add(String.format(
                    "ルール説明 \"%s\" は stored.rules 上では %s を指しているが、"
                        + "本テストの期待は %s（UUID 取り違え。本テストの EXPECTATIONS を"
                        + "実際の stored.rules に合わせて修正すること）",
                    expectation.ruleDescription(), actualStoreFile, expectation.storeFileName()));
            }
        }

        if (mismatches.isEmpty()) {
            return;
        }
        fail("ArchUnit 凍結ストアの UUID 対応がずれています:\n"
            + String.join("\n", mismatches));
    }

    @Test
    @DisplayName("七つの凍結ストアは上限以内で重複・整列崩れ・形式不正がない")
    void 凍結ストアの行数が期待値と一致する() throws IOException {
        verifyStoreCounts(STORE_DIR, EXPECTATIONS);
    }

    private static final String TYPE = "[\\p{javaJavaIdentifierPart}.$]+";
    private static final String LOCATION = "\\([^\\r\\n]+\\.java:[0-9]+\\)";
    private static final String DEPENDENCY = " \\| dependency: .+ \\[occurrence=[1-9][0-9]*]";
    private static final Map<String, Pattern> ROW_FORMATS = Map.of(
        "public controller endpoints must have an authorization signal (Wave4)",
        Pattern.compile("\\S+\\([^\\r\\n]*\\) is a public Controller Mapping endpoint without an authorization signal "
            + Pattern.quote("(@PreAuthorize / AccessControlService / ContentVisibilityChecker / *AccessGuard / *AccessService call)")
            + " at " + LOCATION),
        "no cross-domain entity dependency (D-1)",
        Pattern.compile(TYPE + " \\(domain '[^']+'\\) depends on other-domain entity " + TYPE
            + " \\(domain '[^']+'\\)" + DEPENDENCY),
        "transactional should not span other-domain repositories (D-3)",
        Pattern.compile(TYPE + " \\(domain '[^']+', @Transactional\\) depends on other-domain repository " + TYPE
            + " \\(domain '[^']+'\\)" + DEPENDENCY),
        "transactional entry should not transitively reach other-domain repositories (D-3T)",
        Pattern.compile("@Transactional entry \\S+\\([^\\r\\n]*\\) \\(domain '[^']+'\\) reaches other-domain repository "
            + TYPE + " \\(domain '[^']+'\\) \\[D-3T]"),
        "entities should extend UuidV7Entity (D-2b)",
        Pattern.compile("Class <" + TYPE + "> is not assignable to com\\.mannschaft\\.app\\.common\\.entity\\.UuidV7Entity in " + LOCATION),
        "no cross-domain repository dependency (D-5)",
        Pattern.compile(TYPE + " \\(domain '[^']+'\\) depends on other-domain repository " + TYPE
            + " \\(domain '[^']+'\\)" + DEPENDENCY),
        "service API must not expose entities in signature (D-1 API boundary)",
        Pattern.compile("\\S+\\([^\\r\\n]*\\) exposes @Entity class " + TYPE + " in its signature at " + LOCATION)
    );

    static void verifyStoreCounts(Path storeDir, List<FrozenStoreExpectation> expectations) throws IOException {
        assertTrue(Files.isDirectory(storeDir), "凍結ストアディレクトリが見つからない: " + storeDir.toAbsolutePath());
        List<String> failures = new ArrayList<>();
        for (FrozenStoreExpectation expectation : expectations) {
            Path file = storeDir.resolve(expectation.storeFileName());
            assertTrue(Files.isRegularFile(file), "凍結ストアファイルが見つからない: " + file.toAbsolutePath());
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int maximum = expectation.expectedLineCount();
            if (lines.size() > maximum) {
                failures.add(expectation.ruleDescription() + ": 上限 " + maximum + " に対して " + lines.size() + " 行");
            }
            Pattern format = ROW_FORMATS.get(expectation.ruleDescription());
            assertTrue(format != null, "形式が未登録のルール: " + expectation.ruleDescription());
            Set<String> seen = new HashSet<>();
            String previous = null;
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                String location = expectation.storeFileName() + ":" + (index + 1);
                if (line.isBlank() || !format.matcher(line).matches()) {
                    failures.add(location + ": 空白行またはルール形式不正: " + line);
                }
                if (!seen.add(line)) {
                    failures.add(location + ": 重複行: " + line);
                }
                if (previous != null && previous.compareTo(line) > 0) {
                    failures.add(location + ": 自然順の整列崩れ: " + line);
                }
                previous = line;
            }
        }
        assertTrue(failures.isEmpty(), () -> "ArchUnit 凍結ストアの健全性違反:\n" + String.join("\n", failures));
    }

    /** ルール説明・凍結ストアファイル名・期待行数の1組。 */
    record FrozenStoreExpectation(
        String ruleDescription, String storeFileName, int expectedLineCount) {
    }
}
