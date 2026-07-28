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
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ArchUnit {@code FreezingArchRule} 凍結ストアの改竄検知番人テスト（認可根治戦役 Ph4・安価版）。
 *
 * <h2>背景（`--tests` 絞り込み実行によるストア破壊事故）</h2>
 * <p>{@code FreezingArchRule} は {@code freeze.refreeze=false}（本リポの既定）で運用しており、
 * 新規の違反が追加された実行では確実に fail する。しかし <b>{@code ./gradlew test --tests "..."}
 * のようにテストクラスを絞り込んで実行すると、その実行では「該当ルールの ArchUnit 解析自体が
 * 走らない、または対象クラスが絞り込まれて検出されない」ため、ストアに凍結済みの違反が
 * 「今回は検出されなかった」と誤認され、{@code refreeze} の書き戻し時に免責リストから
 * 削除されてしまう</b>という事故が過去に複数回発生した
 * （{@code memory/feedback_archunit_freeze_store_corrupted_by_filtered_test_run}）。
 *
 * <p>免責リストから違反が消えるのは一見「改善」に見えるが、実態は
 * <b>「解消されていない認可の穴が番人の監視対象から静かに脱落する」</b>という重大な後退である。
 * 本テストはこの事故を「ストアファイルの行数（＝凍結された違反件数）が想定と食い違っていないか」
 * という機械的なチェックで検知する。
 *
 * <h2>判定方針</h2>
 * <ul>
 *   <li><b>行数が期待値どおり</b>: 合格。</li>
 *   <li><b>行数が期待値を上回った場合</b>（＝新規違反が凍結された）: {@code refreeze=false} の
 *       既定挙動では本来起こり得ないため、無条件で fail させる（新規違反の混入シグナル）。</li>
 *   <li><b>行数が期待値を下回った場合</b>: 「違反を正しく根治して減った」正常なケースと、
 *       「{@code --tests} 絞り込み実行でストアが誤って書き戻された」事故のケースを、この場では
 *       機械的に区別できない。そのため <b>いずれの場合も一旦 fail させ</b>、
 *       「意図した根治であれば {@code EXPECTED_LINE_COUNT} 定数を実測値に更新してコミットする」
 *       という運用を強制する。これにより「ストアが減った」という事実を必ず人間（レビュアー）の
 *       目に触れさせ、正当な根治か事故かをコミット差分で説明させる。</li>
 * </ul>
 *
 * <h2>本テスト自身の安全性</h2>
 * <p>本テストは凍結ストアファイルを<b>読み取るだけ</b>で、ArchUnit の解析やストアへの
 * 書き戻しは一切行わない。したがって {@code --tests} で本テストのみを絞り込んで実行しても、
 * 本テストが検知しようとしている事故（ストアの誤った書き戻し）を自ら引き起こすことはない。
 * ただし、他の ArchUnit 番人テスト（{@link AuthzControllerGuardArchTest} 等）を絞り込み実行すると
 * 事故が起きるため、それらは必ずフル {@code ./gradlew test} で実行すること。</p>
 */
class ArchUnitFreezeStoreIntegrityTest {

    /** 凍結ストアのルートディレクトリ（{@code backend} をカレントディレクトリとして解決）。 */
    private static final Path STORE_DIR =
        Paths.get("src", "test", "resources", "archunit_store");

    private static final Path STORED_RULES_FILE = STORE_DIR.resolve("stored.rules");

    /**
     * 認可番人ストア（Wave4）の期待行数。
     *
     * <p><b>根治で行数が減った場合の更新手順</b>: {@code git diff --stat
     * backend/src/test/resources/archunit_store/9ed4737d-c74f-4374-923e-4663d3c9e256} で
     * 実際に違反が解消されたことを確認した上で、この定数を実測行数
     * （{@code wc -l backend/src/test/resources/archunit_store/9ed4737d-c74f-4374-923e-4663d3c9e256}）
     * に更新し、ストアファイルの変更と同じコミットに含めること。
     *
     * <p>795 → 783（2026-07-28・認可根治 Wave7）: safetycheck / school / proxy の 12 EP に
     * per-scope 認可を敷設し、番人が「認可シグナルあり」と判定するようになったため凍結ストアから
     * 解消。内訳は {@code SafetyCheckController}（listSafetyChecks / getSafetyCheck / getHistory）3、
     * {@code SafetyTemplateController}（listTemplates / getTemplate / createTemplate / updateTemplate）4、
     * {@code SafetyFollowupController.updateFollowup} 1、{@code FamilyAttendanceNoticeController}
     * （getTeamNotices / acknowledgeNotice / applyToRecord）3、
     * {@code ProxyMonthlySummaryController.getDownloadUrl} 1。
     * 違反隠蔽ではなく正当な根治に伴う縮小（同一 PR で {@code *ScopeContractIT} を新設して検証）。</p>
     */
    private static final int EXPECTED_LINES_AUTHZ_WAVE4 = 783;

    /**
     * クロスドメイン Entity 参照禁止ストア（D-1）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 584c3a46-b9c1-4cc2-bf74-e0a18eab1bef}）。
     *
     * <p>2138 → 2135（2026-07-23）: {@code admin.controller.SystemAdminDashboardController} の
     * 一覧3エンドポイントを Summary DTO 返却に是正し、Controller からの他ドメイン Entity 参照
     * 3 件（auth.UserEntity / organization.OrganizationEntity / team.TeamEntity）が根治で解消。
     * 違反隠蔽ではなく正当な負債返済に伴う縮小。</p>
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_ENTITY_D1 = 2135;

    /**
     * 越境 {@code @Transactional} 禁止ストア（D-3）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code f14374b1-655e-4df2-8e82-2d79c8df9174}）。
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_TX_D3 = 1508;

    /**
     * {@code UuidV7Entity} 継承ストア（D-2b）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 2c0ba995-682e-4f80-a5a5-f68c835b720d}）。
     *
     * <p>F20.3（2026-07-22）: {@code billing.beta.BetaPerkCriteriaEntity}（付与条件マスタ）を 1 件追加し
     * 564 → 565。マスタ例外（全テナント共通・複合自然キー {@code (beta_phase, grant_kind)}・独立発番不要）で
     * CLAUDE.md 原則 #6 の明記された例外に該当し、設計是認済み（設計書 F20.3 01 §0/§2）。違反隠蔽ではなく
     * 設計是認例外の正規登録（{@code village.VillageFestivalLivePostEntity} と同型）。</p>
     */
    private static final int EXPECTED_LINES_UUID_V7_D2B = 565;

    /**
     * 越境 Repository 依存禁止ストア（D-5）の期待行数。
     * 更新手順は {@link #EXPECTED_LINES_AUTHZ_WAVE4} と同様（対象ファイル:
     * {@code 427c445d-37ce-4d6e-b095-a1733efe209f}）。
     *
     * <p>D-5 導入（2026-07-24）: D-3 の {@code @Transactional} 前提を外した一般化ルール
     * （{@link CrossDomainRepositoryDependencyArchTest}）の初期凍結。既存負債の台帳であり、
     * 新規の越境 Repository 依存のみを fail させる。返済（chip-away）で行数が減った場合のみ
     * この定数を実測値へ更新する。</p>
     */
    private static final int EXPECTED_LINES_CROSS_DOMAIN_REPO_D5 = 2025;

    /** ルール説明（{@code stored.rules} のキー）・ストアファイル名・期待行数の対応表。 */
    private static final List<FrozenStoreExpectation> EXPECTATIONS = List.of(
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
            "entities should extend UuidV7Entity (D-2b)",
            "2c0ba995-682e-4f80-a5a5-f68c835b720d",
            EXPECTED_LINES_UUID_V7_D2B),
        new FrozenStoreExpectation(
            "no cross-domain repository dependency (D-5)",
            "427c445d-37ce-4d6e-b095-a1733efe209f",
            EXPECTED_LINES_CROSS_DOMAIN_REPO_D5)
    );

    @Test
    @DisplayName("stored.rulesのルール説明→ストアファイルUUID対応がずれていない（UUID取り違え検知）")
    void ストアUUID対応の裏取り() throws IOException {
        assertTrue(Files.isRegularFile(STORED_RULES_FILE),
            "stored.rules が見つからない: " + STORED_RULES_FILE.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        Properties storedRules = new Properties();
        try (InputStream in = Files.newInputStream(STORED_RULES_FILE)) {
            storedRules.load(in);
        }

        List<String> mismatches = new ArrayList<>();
        for (FrozenStoreExpectation expectation : EXPECTATIONS) {
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
    @DisplayName("5つの凍結ストアの行数(=凍結された違反件数)が想定から不自然に増減していない"
        + "（--tests絞り込み実行によるストア破壊事故の検知）")
    void 凍結ストアの行数が期待値と一致する() throws IOException {
        assertTrue(Files.isDirectory(STORE_DIR),
            "ArchUnit 凍結ストアディレクトリが見つからない: " + STORE_DIR.toAbsolutePath()
                + "（CWD=" + Paths.get("").toAbsolutePath() + "）");

        List<String> failures = new ArrayList<>();
        for (FrozenStoreExpectation expectation : EXPECTATIONS) {
            Path storeFile = STORE_DIR.resolve(expectation.storeFileName());
            assertTrue(Files.isRegularFile(storeFile),
                "凍結ストアファイルが見つからない: " + storeFile.toAbsolutePath()
                    + "（ルール: " + expectation.ruleDescription() + "）");

            int actualLines = countLines(storeFile);
            int expectedLines = expectation.expectedLineCount();

            if (actualLines == expectedLines) {
                continue;
            }

            if (actualLines > expectedLines) {
                failures.add(String.format(
                    "%n【新規違反の凍結を検知】ルール \"%s\"（ストア: %s）の行数が %d → %d "
                        + "に増加しています（+%d 件）。%n"
                        + "freeze.refreeze=false の既定では新規違反は本来 fail するはずであり、"
                        + "行数が増えた状態でストアが書き戻されるのは想定外です。%n"
                        + "対処: 新規に追加したコードが認可番人ルールに違反していないか確認し、"
                        + "違反であれば実装を修正してください。意図的に新規違反を凍結許容する場合のみ、"
                        + "レビューで理由を明記した上で本テストの期待値定数を %d に更新してください。",
                    expectation.ruleDescription(), expectation.storeFileName(),
                    expectedLines, actualLines, actualLines - expectedLines, actualLines));
            } else {
                int decreased = expectedLines - actualLines;
                failures.add(String.format(
                    "%n【凍結ストアの行数減少を検知】ルール \"%s\"（ストア: %s）の行数が %d → %d "
                        + "に減少しています（-%d 件）。%n"
                        + "このテストは「正しい根治で違反が解消され行数が減ること」自体は失敗とみなしません。"
                        + "ただし過去に `./gradlew test --tests \"...\"` のようなテスト絞り込み実行で "
                        + "FreezingArchRule が「今回検出されなかった違反」をストアから誤って削除し、"
                        + "解消されていない認可の穴が監視対象から静かに脱落する事故が複数回発生しています。%n"
                        + "対処: %n"
                        + "  (1) 本当に対象ルールの違反を根治する変更を行った場合 → "
                        + "`git diff backend/src/test/resources/archunit_store/%s` "
                        + "で解消された違反の内容を確認し、正当な根治であることを確認した上で、"
                        + "本テストの期待値定数（EXPECTED_LINES_...）を %d に更新して同じコミットに含めてください。%n"
                        + "  (2) 心当たりがない場合（--tests 絞り込み実行をした覚えがある等） → "
                        + "事故の可能性が高いです。`git checkout -- "
                        + "backend/src/test/resources/archunit_store/%s` でストアファイルを復元し、"
                        + "フルの `./gradlew test` で再実行してください。",
                    expectation.ruleDescription(), expectation.storeFileName(),
                    expectedLines, actualLines, decreased,
                    expectation.storeFileName(), actualLines, expectation.storeFileName()));
            }
        }

        if (failures.isEmpty()) {
            return;
        }
        fail("ArchUnit 凍結ストアの行数が期待値と一致しません（本テストの意義: "
            + "backend/.claudecode.md の ArchUnit 番人節を参照）。\n"
            + String.join("\n", failures));
    }

    /** ファイルの行数を数える（{@code wc -l} と同じ「改行区切りの論理行数」）。 */
    private static int countLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    /** ルール説明・凍結ストアファイル名・期待行数の1組。 */
    private record FrozenStoreExpectation(
        String ruleDescription, String storeFileName, int expectedLineCount) {
    }
}
