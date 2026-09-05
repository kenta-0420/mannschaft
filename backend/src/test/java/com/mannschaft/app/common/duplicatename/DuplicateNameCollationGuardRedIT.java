package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260901-1538 柱③-A「組織・チーム名称の重複許可」受け入れ条件テスト（試練で red として設置し、
 * 出陣で {@link DuplicateNameGuardServiceImpl} を実装して green 化した）。
 *
 * <p>同名判定は MySQL 照合順序 {@code utf8mb4_0900_ai_ci}（アクセント・大文字小文字を区別しない）の
 * {@code =} 比較＋前後 trim で行う設計であり、実 DB でしか検証できないため
 * {@link AbstractMySqlIntegrationTest}（Testcontainers）で回す。</p>
 *
 * <h2>検証の二層</h2>
 * <ul>
 *   <li><b>Repository 層</b>: {@link OrganizationRepository#findActiveByNormalizedName}
 *       / {@link TeamRepository#findActiveByNormalizedName} が trim・大小文字違いの同名を
 *       実 DB の照合順序で正しく拾うことを検証する（AC-05）。</li>
 *   <li><b>Guard 層</b>: 上記 Repository の結果を候補供給コールバックとして
 *       {@link DuplicateNameGuardService#checkForCreateAndRun} に渡し、実 DB の同名判定結果に
 *       基づいて確認要求（409）に到達することを検証する（AC-01/AC-02/AC-05 の統合）。</li>
 * </ul>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-05a 前後空白違いの同名は実 DB で一致判定される（Repository層）
 *       → {@link #ac05a_trimmedWhitespaceMatchesViaRealDb()}</li>
 *   <li>AC-05b 大文字小文字違い（ai_ci）の同名は実 DB で一致判定される（Repository層）
 *       → {@link #ac05b_caseInsensitiveMatchesViaRealDb()}</li>
 *   <li>AC-01/AC-02/AC-05 実 DB の同名候補を Guard 層へ渡すと確認要求（409）に到達する
 *       → {@link #ac01_ac02_ac05_guardConsultsRealDbCandidatesAndRequiresConfirmation()}</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 照合順序統合テスト")
// 検分第3巡是正: duplicate_name_locks への行ロック取得（INSERT ... ON DUPLICATE KEY UPDATE）は
// 呼び出し元のトランザクション内で実行する契約のため、Guard を直接呼ぶテストは @Transactional が必要
// （なければ jakarta.persistence.TransactionRequiredException になる）。ロールバックにより
// 後始末も兼ねる。
@Transactional
class DuplicateNameCollationGuardRedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private DuplicateNameGuardService duplicateNameGuardService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("検分第4巡是正(P1-2): FOR UPDATE候補検索がname_trimmedの索引を"
            + "候補として使えることをEXPLAINで実測する")
    void r4_forUpdateCandidateQueryCanUseIndexOnNameTrimmed() {
        // 注記: テスト DB はテーブルの行数が極small（数行）なため、MySQL のオプティマイザが
        // コスト計算の結果「全表スキャンの方が安い」と判断し、実際に選択される実行計画
        // （EXPLAIN の key 列）が索引を使わない可能性がある（これは行数が少ない場合の
        // 正しい最適化であり、索引が機能していない証拠ではない）。そのため本テストは
        // 「索引が候補（possible_keys）に挙がること」＝スキーマ上索引が存在し、
        // このクエリ形状（name_trimmed = TRIM(?)）に対して有効であることを検証する
        // （本番相当のデータ量下での実際の選択は EXPLAIN 実測を PR 本文に記録する）。
        String orgSuffix = shortSuffixDigits();
        String orgName = "EXPLAIN検証組織" + orgSuffix;
        OrganizationEntity org = OrganizationEntity.builder()
                .name(orgName)
                .slug(shortSuffix("dupn-explain-org-"))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);

        Map<String, Object> orgPlan = jdbcTemplate.queryForMap(
                "EXPLAIN SELECT * FROM organizations "
                        + "WHERE deleted_at IS NULL AND lifecycle_status = 'ACTIVE' "
                        + "AND name_trimmed = TRIM(?) FOR UPDATE",
                orgName);
        System.out.println("[柱③-A P1-2 EXPLAIN実測] organizations: " + orgPlan);
        assertThat(orgPlan.get("possible_keys")).as("organizations EXPLAIN: %s", orgPlan)
                .asString().contains("idx_organizations_name_trimmed");

        String teamSuffix = shortSuffixDigits();
        String teamName = "ExplainTeam" + teamSuffix;
        TeamEntity team = TeamEntity.builder()
                .name(teamName)
                .slug(shortSuffix("dupn-explain-team-"))
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
        teamRepository.saveAndFlush(team);

        Map<String, Object> teamPlan = jdbcTemplate.queryForMap(
                "EXPLAIN SELECT * FROM teams "
                        + "WHERE deleted_at IS NULL AND lifecycle_status = 'ACTIVE' "
                        + "AND name_trimmed = TRIM(?) FOR UPDATE",
                teamName);
        System.out.println("[柱③-A P1-2 EXPLAIN実測] teams: " + teamPlan);
        assertThat(teamPlan.get("possible_keys")).as("teams EXPLAIN: %s", teamPlan)
                .asString().contains("idx_teams_name_trimmed");
    }

    @Test
    @DisplayName("AC-05a: 前後半角スペース違いの組織名は実DBのname_trimmed(生成列)経由で同名判定される")
    void ac05a_trimmedWhitespaceMatchesViaRealDb() {
        // 検分第5巡是正: name はあえて前後に半角スペースを付けたまま保存する（Java 側で
        // 事前に trim しない）。これにより DB 側の生成列 name_trimmed
        // （GENERATED ALWAYS AS (TRIM(name)) STORED）が実際に半角スペースを除去することを
        // 検証できる。クエリ側は DuplicateNameNormalizer#trimSpaces で正規化済みの値を渡す
        // （リポジトリのクエリ自体はもう TRIM() しない契約のため）。
        String rawNameWithSpaces = "  照合順序テスト組織" + shortSuffixDigits() + "  ";
        OrganizationEntity org = OrganizationEntity.builder()
                .name(rawNameWithSpaces)
                .slug(shortSuffix("dupn-org-"))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);

        List<OrganizationEntity> found = organizationRepository.findActiveByNormalizedName(
                DuplicateNameNormalizer.trimSpaces(rawNameWithSpaces));

        assertThat(found).extracting(OrganizationEntity::getId).contains(org.getId());
    }

    @Test
    @DisplayName("検分第5巡是正: 半角スペースのみの前後trim違いは同名判定される"
            + "（DuplicateNameNormalizer#trimSpacesとMySQL TRIM()の基準一致）")
    void r5_spaceOnlyTrimMatchesAcrossJavaAndMysql() {
        String bareName = "スペース正規化検証組織" + shortSuffixDigits();
        OrganizationEntity org = OrganizationEntity.builder()
                .name(bareName)
                .slug(shortSuffix("dupn-space-"))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);

        // クエリ側の入力に半角スペースを付けても、trimSpaces後は既存の bareName と一致する。
        String queryWithSpaces = " " + bareName + " ";
        List<OrganizationEntity> found = organizationRepository.findActiveByNormalizedName(
                DuplicateNameNormalizer.trimSpaces(queryWithSpaces));

        assertThat(found).extracting(OrganizationEntity::getId)
                .as("半角スペースのみの前後trim違いは同名として扱われるはず")
                .contains(org.getId());
    }

    @Test
    @DisplayName("検分第5巡是正: 末尾タブ付きの名称はMySQL TRIM()の仕様通り別名扱いになる"
            + "（タブは除去されないため、既存の同名と衝突しない）")
    void r5_tabSuffixedNameIsTreatedAsDifferentNamePerMysqlTrimSemantics() {
        String bareName = "タブ検証組織" + shortSuffixDigits();
        OrganizationEntity org = OrganizationEntity.builder()
                .name(bareName)
                .slug(shortSuffix("dupn-tab-"))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);

        // "bareName\t"（末尾タブ）は DuplicateNameNormalizer#trimSpaces では除去されない
        // （半角スペースのみ trim するため）。MySQL の TRIM() も同じくタブを除去しないため、
        // name_trimmed 列の値は "bareName\t" のまま残り、"bareName" とは別名として扱われる
        // （＝候補として検出されない）。これが検分第5巡で修正した不整合そのものであり、
        // 「タブ付き入力は既存の同名と衝突しない」という MySQL TRIM() 準拠の仕様を保証する。
        String tabSuffixedInput = bareName + "\t";
        String normalizedTabSuffixed = DuplicateNameNormalizer.trimSpaces(tabSuffixedInput);
        // trimSpaces はタブを除去しないため、正規化後もタブは残ったままである。
        assertThat(normalizedTabSuffixed).isEqualTo(tabSuffixedInput);

        List<OrganizationEntity> found =
                organizationRepository.findActiveByNormalizedName(normalizedTabSuffixed);

        assertThat(found).extracting(OrganizationEntity::getId)
                .as("末尾タブ付き名称はMySQL TRIM()準拠で別名扱いになり、既存の同名候補に含まれないはず")
                .doesNotContain(org.getId());
    }

    @Test
    @DisplayName("AC-05b: 大文字小文字違いのチーム名は実DBのutf8mb4_0900_ai_ci比較で同名判定される")
    void ac05b_caseInsensitiveMatchesViaRealDb() {
        String suffix = shortSuffixDigits();
        TeamEntity team = TeamEntity.builder()
                .name("CollationTeam" + suffix)
                .slug(shortSuffix("dupn-team-"))
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
        teamRepository.saveAndFlush(team);

        List<TeamEntity> found =
                teamRepository.findActiveByNormalizedName("collationteam" + suffix);

        assertThat(found).extracting(TeamEntity::getId).contains(team.getId());
    }

    @Test
    @DisplayName("AC-01/AC-02/AC-05: 実DBの同名候補をGuard層へ渡すと確認要求(409)に到達する")
    void ac01_ac02_ac05_guardConsultsRealDbCandidatesAndRequiresConfirmation() {
        String suffix = shortSuffixDigits();
        String name = "重複確認IT組織" + suffix;
        OrganizationEntity org = OrganizationEntity.builder()
                .name(name)
                .slug(shortSuffix("dupn-it-"))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build();
        organizationRepository.saveAndFlush(org);

        assertThatThrownBy(() -> duplicateNameGuardService.checkForCreateAndRun(
                DuplicateNameScopeKind.ORGANIZATION, name, 999L, false, null,
                () -> organizationRepository.findActiveByNormalizedName(name).stream()
                        .map(o -> new DuplicateNameCandidate(
                                String.valueOf(o.getId()),
                                o.getVisibility() == OrganizationEntity.Visibility.PUBLIC,
                                o.getVisibility() == OrganizationEntity.Visibility.PUBLIC ? o.getName() : null))
                        .toList(),
                () -> "should-not-be-created"))
                .isInstanceOf(DuplicateNameConfirmationRequiredException.class)
                .satisfies(ex -> {
                    DuplicateNameConfirmationDetails details =
                            ((DuplicateNameConfirmationRequiredException) ex).getDetails();
                    assertThat(details.visibleCandidates()).hasSize(1);
                    assertThat(details.visibleCandidates().get(0).name()).isEqualTo(name);
                });
    }

    /**
     * slug 列（VARCHAR(30)）に収まる一意なテスト用 slug を生成する。
     * {@code System.nanoTime()} をそのまま連結すると 30 文字を超えるため、下位桁のみ使用する。
     */
    private static String shortSuffix(String prefix) {
        String digits = shortSuffixDigits();
        return (prefix + digits).substring(0, Math.min(30, (prefix + digits).length()));
    }

    private static String shortSuffixDigits() {
        return Long.toString(System.nanoTime() % 100_000_000L);
    }
}
