package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F15.4: {@link TeamSearchSpecifications} の結合テスト。
 *
 * <p>設計書 {@code docs/features/F15.4_team_store_search_within_org.md §11.1} に対応。
 * 実 MySQL (Testcontainers) に対し各 Specification 単体の WHERE 句挙動を検証する。</p>
 *
 * <h3>カバー観点</h3>
 * <ul>
 *   <li>{@link TeamSearchSpecifications#notDeleted()} — {@code @SQLRestriction} と協調して deleted 行を除外する</li>
 *   <li>{@link TeamSearchSpecifications#notArchived()} — archivedAt が NULL の行のみ返す</li>
 *   <li>{@link TeamSearchSpecifications#belongsToOrganization(Long)} — ACTIVE メンバーシップのある orgId 配下のチームだけ返す</li>
 *   <li>{@link TeamSearchSpecifications#visibilityIn(java.util.Set)} — 許可可視性のみ</li>
 *   <li>{@link TeamSearchSpecifications#nameOrKanaContains(String)} — 部分一致 + LIKE メタ文字エスケープ</li>
 *   <li>{@link TeamSearchSpecifications#prefectureEquals(String)} / {@code cityEquals} / {@code templateEquals}</li>
 *   <li>null / 空文字パススルー（恒真）</li>
 * </ul>
 *
 * <p><b>セットアップ方針</b>: {@code ActivityResultVisibilityProjectionRepositoryTest} を踏襲し、
 * {@code ddl-auto=create-drop}・{@code @Transactional} ロールバックで隔離する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TeamSearchSpecifications 結合テスト")
class TeamSearchSpecificationsTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private TeamRepository teamRepository;

    @PersistenceContext
    private EntityManager em;

    private static final AtomicInteger slugCounter = new AtomicInteger(0);

    private static String nextSlug() {
        return "t-" + slugCounter.incrementAndGet();
    }

    private static final Long ORG_A = 1001L;
    private static final Long ORG_B = 1002L;

    private final Pageable pageable = PageRequest.of(0, 50);

    @BeforeEach
    void setUp() {
        // ベースクリア（DDL は create-drop だがテスト毎の隔離は @Transactional に任せる）
    }

    // ════════════════════════════════════════════════════════════
    // notDeleted / notArchived
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("notDeleted / notArchived")
    class NotDeletedNotArchived {

        @Test
        @DisplayName("notDeleted: @SQLRestriction と協調し deletedAt が NULL の行のみ返す")
        void notDeleted_excludesSoftDeleted() {
            Long aliveId = persistTeam("生存店舗", "せいぞん", TeamEntity.Visibility.PUBLIC, null, null);
            persistDeletedTeam("削除店舗", "さくじょ");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.notDeleted(), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(aliveId)
                    // @SQLRestriction が削除済みを完全除外するため、結果に含まれない
                    .noneMatch(id -> teamRepository.findById(id).isEmpty());
        }

        @Test
        @DisplayName("notArchived: archivedAt が NULL の行のみ返す")
        void notArchived_excludesArchived() {
            Long aliveId = persistTeam("アクティブ", "あくてぃぶ", TeamEntity.Visibility.PUBLIC, null, null);
            Long archivedId = persistArchivedTeam("凍結", "とうけつ");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.notArchived(), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(aliveId)
                    .doesNotContain(archivedId);
        }
    }

    // ════════════════════════════════════════════════════════════
    // belongsToOrganization
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("belongsToOrganization")
    class BelongsToOrganization {

        @Test
        @DisplayName("指定組織の ACTIVE メンバーシップを持つチームのみ返す")
        void onlyActiveMembershipMatches() {
            Long teamInOrgA = persistTeam("組織A店舗", "そしきえー", TeamEntity.Visibility.PUBLIC, null, null);
            Long teamInOrgB = persistTeam("組織B店舗", "そしきびー", TeamEntity.Visibility.PUBLIC, null, null);
            Long unrelated = persistTeam("無所属", "むしょぞく", TeamEntity.Visibility.PUBLIC, null, null);

            persistMembership(teamInOrgA, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
            persistMembership(teamInOrgB, ORG_B, TeamOrgMembershipEntity.Status.ACTIVE);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.belongsToOrganization(ORG_A), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .containsExactlyInAnyOrder(teamInOrgA)
                    .doesNotContain(teamInOrgB, unrelated);
        }

        @Test
        @DisplayName("PENDING メンバーシップは除外される（ACTIVE のみ）")
        void pendingMembershipExcluded() {
            Long active = persistTeam("アクティブ店", "あくてぃぶてん", TeamEntity.Visibility.PUBLIC, null, null);
            Long pending = persistTeam("申請中店", "しんせいちゅう", TeamEntity.Visibility.PUBLIC, null, null);

            persistMembership(active, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
            persistMembership(pending, ORG_A, TeamOrgMembershipEntity.Status.PENDING);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.belongsToOrganization(ORG_A), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(active)
                    .doesNotContain(pending);
        }

        @Test
        @DisplayName("orgId=null: 全件パススルー（恒真）")
        void nullOrgId_passesThroughAll() {
            Long t1 = persistTeam("店舗1", "てんぽ1", TeamEntity.Visibility.PUBLIC, null, null);
            Long t2 = persistTeam("店舗2", "てんぽ2", TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.belongsToOrganization(null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t1, t2);
        }
    }

    // ════════════════════════════════════════════════════════════
    // visibilityIn
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("visibilityIn")
    class VisibilityIn {

        @Test
        @DisplayName("許可集合に含まれる可視性のみ返す")
        void onlyAllowedVisibilities() {
            Long publicTeam = persistTeam("公開", "こうかい", TeamEntity.Visibility.PUBLIC, null, null);
            Long guestsAndAbove = persistTeam("ゲスト以上", "げすといじょう", TeamEntity.Visibility.GUESTS_AND_ABOVE, null, null);
            Long membersAndAbove = persistTeam("メンバー以上", "めんばーいじょう", TeamEntity.Visibility.MEMBERS_AND_ABOVE, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.visibilityIn(EnumSet.of(TeamEntity.Visibility.PUBLIC)),
                    pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(publicTeam)
                    .doesNotContain(guestsAndAbove, membersAndAbove);
        }

        @Test
        @DisplayName("PUBLIC + GUESTS_AND_ABOVE 集合: MEMBERS_AND_ABOVE のみ除外")
        void publicAndGuestsAndAbove_excludesMembersAndAbove() {
            Long publicTeam = persistTeam("公開", "こうかい", TeamEntity.Visibility.PUBLIC, null, null);
            Long guestsAndAbove = persistTeam("ゲスト以上", "げすといじょう", TeamEntity.Visibility.GUESTS_AND_ABOVE, null, null);
            Long membersAndAbove = persistTeam("メンバー以上", "めんばーいじょう", TeamEntity.Visibility.MEMBERS_AND_ABOVE, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.visibilityIn(
                            EnumSet.of(TeamEntity.Visibility.PUBLIC, TeamEntity.Visibility.GUESTS_AND_ABOVE)),
                    pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(publicTeam, guestsAndAbove)
                    .doesNotContain(membersAndAbove);
        }

        @Test
        @DisplayName("null: 全件パススルー（恒真）")
        void nullAllowed_passesThroughAll() {
            Long publicTeam = persistTeam("公開", "こうかい", TeamEntity.Visibility.PUBLIC, null, null);
            Long guestsAndAbove = persistTeam("ゲスト以上", "げすといじょう", TeamEntity.Visibility.GUESTS_AND_ABOVE, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.visibilityIn(null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(publicTeam, guestsAndAbove);
        }

        @Test
        @DisplayName("空集合: 全件パススルー（恒真）")
        void emptyAllowed_passesThroughAll() {
            Long publicTeam = persistTeam("公開", "こうかい", TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.visibilityIn(Set.of()), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(publicTeam);
        }
    }

    // ════════════════════════════════════════════════════════════
    // nameOrKanaContains — 部分一致 + LIKE エスケープ
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("nameOrKanaContains")
    class NameOrKanaContains {

        @Test
        @DisplayName("name 部分一致: ヒットする")
        void name_match() {
            Long hit = persistTeam("整骨院ABC", "せいこついんえーびーしー",
                    TeamEntity.Visibility.PUBLIC, null, null);
            Long miss = persistTeam("無関係店", "むかんけいてん",
                    TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("整骨"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(hit)
                    .doesNotContain(miss);
        }

        @Test
        @DisplayName("nameKana 部分一致: ヒットする")
        void nameKana_match() {
            Long hit = persistTeam("ABC", "せいこつ", TeamEntity.Visibility.PUBLIC, null, null);
            Long miss = persistTeam("DEF", "むかんけい", TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("せいこつ"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(hit)
                    .doesNotContain(miss);
        }

        @Test
        @DisplayName("null: 全件パススルー（恒真）")
        void nullKeyword_passesThroughAll() {
            Long t = persistTeam("X", "えっくす", TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains(null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }

        @Test
        @DisplayName("空文字: 全件パススルー（恒真）")
        void blankKeyword_passesThroughAll() {
            Long t = persistTeam("Y", "わい", TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("   "), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }

        @Test
        @DisplayName("LIKE エスケープ: '%' を含むキーワードはリテラルマッチする（全件マッチしない）")
        void likeEscape_percent() {
            // 名前にリテラル '%' を含む店だけがヒットすべき
            Long literalPercent = persistTeam("セール100%店", "せーる100ぱー",
                    TeamEntity.Visibility.PUBLIC, null, null);
            // '%' を含まないが他に文字を含む店は誤ってヒットしてはいけない
            Long noPercent = persistTeam("通常店", "つうじょう",
                    TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            // エスケープが効いていなければ '%' は SQL のワイルドカードとして解釈され全件マッチする
            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("100%"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(literalPercent)
                    .doesNotContain(noPercent);
        }

        @Test
        @DisplayName("LIKE エスケープ: '_' を含むキーワードはリテラルマッチする（1文字ワイルドカードにならない）")
        void likeEscape_underscore() {
            Long literalUnderscore = persistTeam("店_A", "てんあんだーえー",
                    TeamEntity.Visibility.PUBLIC, null, null);
            Long noUnderscore = persistTeam("店X", "てんえっくす",
                    TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            // '_' がエスケープされていなければ任意の 1 文字とマッチし "店X" もヒットしてしまう
            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("店_"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(literalUnderscore)
                    .doesNotContain(noUnderscore);
        }

        @Test
        @DisplayName("LIKE エスケープ: バックスラッシュを含むキーワードもリテラルマッチする")
        void likeEscape_backslash() {
            // MySQL の文字列リテラルではバックスラッシュ二重化が必要。
            // Builder 経由で名前にバックスラッシュを含めることで挙動を検証する。
            Long literalBackslash = persistTeam("パス\\店", "ぱすばっくすらっしゅ",
                    TeamEntity.Visibility.PUBLIC, null, null);
            Long noBackslash = persistTeam("普通店", "ふつうてん",
                    TeamEntity.Visibility.PUBLIC, null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.nameOrKanaContains("\\"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(literalBackslash)
                    .doesNotContain(noBackslash);
        }
    }

    // ════════════════════════════════════════════════════════════
    // prefectureEquals / cityEquals / templateEquals
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("prefecture / city / template の equals 系")
    class EqualsSpecs {

        @Test
        @DisplayName("prefectureEquals: 完全一致のみ")
        void prefecture_exactMatch() {
            Long tokyo = persistTeamFull("店A", "てんえー", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");
            Long osaka = persistTeamFull("店B", "てんびー", TeamEntity.Visibility.PUBLIC,
                    "大阪府", "梅田", "salon");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureEquals("東京都"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(tokyo)
                    .doesNotContain(osaka);
        }

        @Test
        @DisplayName("prefectureEquals(null): 全件パススルー")
        void prefecture_nullPassesThrough() {
            Long t = persistTeamFull("店", "てん", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureEquals(null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }

        @Test
        @DisplayName("cityEquals: 完全一致のみ")
        void city_exactMatch() {
            Long shibuya = persistTeamFull("店A", "てんえー", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");
            Long shinjuku = persistTeamFull("店B", "てんびー", TeamEntity.Visibility.PUBLIC,
                    "東京都", "新宿区", "salon");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.cityEquals("渋谷区"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(shibuya)
                    .doesNotContain(shinjuku);
        }

        @Test
        @DisplayName("cityEquals(空文字): 全件パススルー")
        void city_blankPassesThrough() {
            Long t = persistTeamFull("店", "てん", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.cityEquals(""), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }

        @Test
        @DisplayName("templateEquals: 完全一致のみ")
        void template_exactMatch() {
            Long salon = persistTeamFull("サロン", "さろん", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");
            Long clinic = persistTeamFull("クリニック", "くりにっく", TeamEntity.Visibility.PUBLIC,
                    "東京都", "新宿区", "clinic");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.templateEquals("salon"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(salon)
                    .doesNotContain(clinic);
        }

        @Test
        @DisplayName("templateEquals(null): 全件パススルー")
        void template_nullPassesThrough() {
            Long t = persistTeamFull("X", "えっくす", TeamEntity.Visibility.PUBLIC,
                    "東京都", "渋谷区", "salon");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.templateEquals(null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }
    }

    // ════════════════════════════════════════════════════════════
    // F22.1 市 Phase 2 足場C: 地域コード（prefectureCode / cityCode）+ dual-support
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("地域コード（F22.1）: code 一致 + dual-support フォールバック")
    class RegionCodeSpecs {

        @Test
        @DisplayName("prefectureCodeEquals: 完全一致のみ")
        void prefectureCode_exactMatch() {
            Long tokyo = persistTeamWithCodes("東京店", "とうきょうてん", "13", "13113");
            Long osaka = persistTeamWithCodes("大阪店", "おおさかてん", "27", "27100");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureCodeEquals("13"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(tokyo)
                    .doesNotContain(osaka);
        }

        @Test
        @DisplayName("cityCodeEquals: 完全一致のみ")
        void cityCode_exactMatch() {
            Long shibuya = persistTeamWithCodes("渋谷店", "しぶやてん", "13", "13113");
            Long shinjuku = persistTeamWithCodes("新宿店", "しんじゅくてん", "13", "13104");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.cityCodeEquals("13113"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(shibuya)
                    .doesNotContain(shinjuku);
        }

        @Test
        @DisplayName("prefectureFilter: code 指定あり → code 一致（名称は無視）")
        void prefectureFilter_codePreferred() {
            // 名称は同じ「東京都」だが code が異なる 2 店
            Long codeMatch = persistTeamFullWithCodes("店A", "てんえー", "東京都", "渋谷区", "13", "13113");
            Long codeMiss = persistTeamFullWithCodes("店B", "てんびー", "東京都", "新宿区", "27", "27100");

            em.flush();
            em.clear();

            // code=13 を指定 → 名称が同じでも code=27 はヒットしない
            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureFilter("13", "東京都"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(codeMatch)
                    .doesNotContain(codeMiss);
        }

        @Test
        @DisplayName("prefectureFilter: code 未指定 → 名称一致にフォールバック（dual-support）")
        void prefectureFilter_nameFallback() {
            Long tokyo = persistTeamFullWithCodes("店A", "てんえー", "東京都", "渋谷区", null, null);
            Long osaka = persistTeamFullWithCodes("店B", "てんびー", "大阪府", "梅田", null, null);

            em.flush();
            em.clear();

            // code=null → 名称 "東京都" にフォールバック
            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureFilter(null, "東京都"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(tokyo)
                    .doesNotContain(osaka);
        }

        @Test
        @DisplayName("cityFilter: code 指定あり → code 一致（名称は無視）")
        void cityFilter_codePreferred() {
            Long codeMatch = persistTeamFullWithCodes("店A", "てんえー", "東京都", "渋谷区", "13", "13113");
            Long codeMiss = persistTeamFullWithCodes("店B", "てんびー", "東京都", "渋谷区", "13", "13104");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.cityFilter("13113", "渋谷区"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(codeMatch)
                    .doesNotContain(codeMiss);
        }

        @Test
        @DisplayName("cityFilter: code 未指定 → 名称一致にフォールバック（dual-support）")
        void cityFilter_nameFallback() {
            Long shibuya = persistTeamFullWithCodes("店A", "てんえー", "東京都", "渋谷区", null, null);
            Long shinjuku = persistTeamFullWithCodes("店B", "てんびー", "東京都", "新宿区", null, null);

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.cityFilter(null, "渋谷区"), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(shibuya)
                    .doesNotContain(shinjuku);
        }

        @Test
        @DisplayName("prefectureFilter(null, null): 全件パススルー（恒真）")
        void prefectureFilter_bothNull_passesThrough() {
            Long t = persistTeamFullWithCodes("店", "てん", "東京都", "渋谷区", "13", "13113");

            em.flush();
            em.clear();

            Page<TeamEntity> result = teamRepository.findAll(
                    TeamSearchSpecifications.prefectureFilter(null, null), pageable);

            assertThat(result.getContent())
                    .extracting(TeamEntity::getId)
                    .contains(t);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 複合 Specification: 実際のサービス層の組み合わせを再現
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("複合: belongsToOrganization + visibilityIn + nameOrKanaContains を AND 合成")
    void compositeSpec_returnsExpectedSubset() {
        // ORG_A 配下に複数チームを配置
        Long publicHit = persistTeamFull("整骨院公開", "せいこついんこうかい",
                TeamEntity.Visibility.PUBLIC, "東京都", "渋谷区", "salon");
        Long guestsAndAboveHit = persistTeamFull("整骨院ゲスト以上", "せいこついんげすと",
                TeamEntity.Visibility.GUESTS_AND_ABOVE, "東京都", "渋谷区", "salon");
        Long membersAndAboveMiss = persistTeamFull("整骨院メンバー以上", "せいこついんめんばー",
                TeamEntity.Visibility.MEMBERS_AND_ABOVE, "東京都", "渋谷区", "salon");
        Long unrelated = persistTeamFull("無関係", "むかんけい",
                TeamEntity.Visibility.PUBLIC, "東京都", "渋谷区", "salon");
        Long orgBMiss = persistTeamFull("整骨院組織B", "せいこついんびー",
                TeamEntity.Visibility.PUBLIC, "東京都", "渋谷区", "salon");

        persistMembership(publicHit, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
        persistMembership(guestsAndAboveHit, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
        persistMembership(membersAndAboveMiss, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
        persistMembership(unrelated, ORG_A, TeamOrgMembershipEntity.Status.ACTIVE);
        persistMembership(orgBMiss, ORG_B, TeamOrgMembershipEntity.Status.ACTIVE);

        em.flush();
        em.clear();

        // メンバー視点: PUBLIC + GUESTS_AND_ABOVE を許可、キーワード "整骨院"
        Specification<TeamEntity> spec = Specification
                .where(TeamSearchSpecifications.notDeleted())
                .and(TeamSearchSpecifications.notArchived())
                .and(TeamSearchSpecifications.belongsToOrganization(ORG_A))
                .and(TeamSearchSpecifications.visibilityIn(
                        EnumSet.of(TeamEntity.Visibility.PUBLIC, TeamEntity.Visibility.GUESTS_AND_ABOVE)))
                .and(TeamSearchSpecifications.nameOrKanaContains("整骨院"));

        Page<TeamEntity> result = teamRepository.findAll(spec, pageable);

        assertThat(result.getContent())
                .extracting(TeamEntity::getId)
                .containsExactlyInAnyOrder(publicHit, guestsAndAboveHit)
                .doesNotContain(membersAndAboveMiss, unrelated, orgBMiss);
    }

    // ════════════════════════════════════════════════════════════
    // ヘルパー
    // ════════════════════════════════════════════════════════════

    private Long persistTeam(String name, String kana, TeamEntity.Visibility v,
                             String prefecture, String city) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(v)
                .supporterEnabled(false)
                .prefecture(prefecture)
                .city(city)
                .build();
        em.persist(team);
        return team.getId();
    }

    private Long persistTeamFull(String name, String kana, TeamEntity.Visibility v,
                                  String prefecture, String city, String template) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(v)
                .supporterEnabled(false)
                .prefecture(prefecture)
                .city(city)
                .template(template)
                .build();
        em.persist(team);
        return team.getId();
    }

    /** F22.1: 地域コード（名称なし）でチームを作る。 */
    private Long persistTeamWithCodes(String name, String kana, String prefectureCode, String cityCode) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
        team.updateRegionCodes(prefectureCode, cityCode);
        em.persist(team);
        return team.getId();
    }

    /** F22.1: 名称 + 地域コードの両方を持つチームを作る。 */
    private Long persistTeamFullWithCodes(String name, String kana, String prefecture, String city,
                                          String prefectureCode, String cityCode) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .prefecture(prefecture)
                .city(city)
                .build();
        team.updateRegionCodes(prefectureCode, cityCode);
        em.persist(team);
        return team.getId();
    }

    private Long persistDeletedTeam(String name, String kana) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .deletedAt(LocalDateTime.now())
                .build();
        em.persist(team);
        return team.getId();
    }

    private Long persistArchivedTeam(String name, String kana) {
        TeamEntity team = TeamEntity.builder()
                .slug(nextSlug())
                .name(name)
                .nameKana(kana)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .archivedAt(LocalDateTime.now())
                .build();
        em.persist(team);
        return team.getId();
    }

    private void persistMembership(Long teamId, Long orgId, TeamOrgMembershipEntity.Status status) {
        TeamOrgMembershipEntity m = TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build();
        em.persist(m);
    }
}
