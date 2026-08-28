package com.mannschaft.app.survey;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyComparisonResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyQuestionEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.repository.SurveyQuestionRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveySeriesService;
import com.mannschaft.app.survey.service.SurveyService;
import com.mannschaft.app.survey.service.SurveyUniverseResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-042 / Issue #2787 — アンケート公開時の {@code target_count} スナップショット統合テスト。
 *
 * <p><b>本テストが守る仕様</b>: 設計書 {@code docs/features/F05.4_survey_vote.md} §1426-1428
 * および §117 が明記する「公開時に配信対象者数をスナップショットする。後からメンバーが増減しても
 * 変わらない」という挙動。実装は名簿指定（TARGETED）経路でしか {@code target_count} を書いておらず、
 * 全員配信（ALL）では永久に 0 のままだった（＝仕様変更ではなく実装漏れの修復）。</p>
 *
 * <p><b>マスター御裁可（2026-08-15）</b>: 分母はスナップショット（固定）だが、
 * 未回答者一覧と督促の宛先は「今いる人」で都度算出したままとする。
 * この非対称は承知の上の判断であり、AC-12 で番人として固定する。</p>
 *
 * <p><b>トランザクション方針</b>: 本クラスは意図的に {@code @Transactional} を付けない。
 * AC-8（公開失敗時に {@code target_count} も保存されない）を証明するには、
 * サービス側トランザクションのロールバックが実 DB に反映されることを別トランザクションから
 * 観測する必要があるためである。フィクスチャの永続化は {@link TransactionTemplate} で包む。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-042 アンケート公開時の対象人数スナップショット")
class SurveyPublishTargetCountSnapshotIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private SurveyService surveyService;

    @Autowired
    private SurveyResultService surveyResultService;

    @Autowired
    private SurveySeriesService surveySeriesService;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private SurveyQuestionRepository questionRepository;

    @Autowired
    private SurveyResponseRepository responseRepository;

    @Autowired
    private SurveyUniverseResolver universeResolver;

    @Autowired
    private TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager em;

    /**
     * テスト間で衝突しないよう slug / email に混ぜる一意サフィックス。
     * teams.slug / organizations.slug は 30 文字上限のため、実行回ごとの短い基数36文字列 +
     * 連番で構成し、prefix も短く保つ（長いと Data truncation で全件落ちる）。
     */
    private static final String RUN_ID = Long.toString(System.nanoTime() % 1_679_616L, 36);
    private static final AtomicLong SEQ = new AtomicLong();

    private static String uniq(String prefix) {
        return prefix + RUN_ID + Long.toString(SEQ.incrementAndGet(), 36);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-1 / AC-2: 公開で母集団の人数が記録される（組織スコープ / チームスコープ）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-1) 全員配信×組織スコープの公開で target_count に母集団人数が記録される")
    void ac1_全員配信組織スコープで対象人数が記録される() {
        Long orgId = txTemplate.execute(s -> insertOrganization(uniq("c42o")));
        for (int i = 0; i < 3; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42om") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER));
        }

        Long surveyId = createPublishableSurvey("ORGANIZATION", orgId, DistributionMode.ALL, false);
        surveyService.publishSurvey("ORGANIZATION", orgId, surveyId);

        assertThat(reloadTargetCount(surveyId))
                .as("組織直属メンバー3名がスナップショットされること")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("(AC-2) 全員配信×チームスコープの公開で target_count に母集団人数が記録される")
    void ac2_全員配信チームスコープで対象人数が記録される() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42t")));
        for (int i = 0; i < 2; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42tm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }

        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.ALL, false);
        surveyService.publishSurvey("TEAM", teamId, surveyId);

        assertThat(reloadTargetCount(surveyId))
                .as("チームメンバー2名がスナップショットされること")
                .isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-3: 陽性対照 — 名簿指定（TARGETED）の従来挙動が変わらない
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-3)[陽性対照] 名簿指定の公開では従来どおり名簿の件数が入る")
    void ac3_名簿指定は従来どおり名簿件数() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42gt")));
        // 名簿に載せない在籍メンバーを 3 名置き、名簿件数（2）と母集団（3+2=5）を意図的にずらす。
        for (int i = 0; i < 3; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42go") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }
        List<Long> targetIds = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42gi") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
            targetIds.add(uid);
        }

        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.TARGETED, false);
        // 名簿登録の従来経路（addTargets）をそのまま使う。ここが本改修で壊れていないことが陽性対照。
        surveyService.addTargets(surveyId, targetIds);
        assertThat(reloadTargetCount(surveyId))
                .as("従来どおり addTargets の時点で名簿件数（2）が入ること")
                .isEqualTo(2);

        surveyService.publishSurvey("TEAM", teamId, surveyId);

        assertThat(reloadTargetCount(surveyId))
                .as("公開後も TARGETED は survey_targets の件数（2）であり、スコープ母集団（5）ではないこと")
                .isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-4: 本件の本体 — 公開後にメンバーが増減しても target_count は変わらない
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-4)[番人] 公開後にメンバーが増減しても target_count は変わらない")
    void ac4_公開後のメンバー増減で対象人数は不変() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42ft")));
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42fm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
            members.add(uid);
        }

        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.ALL, false);
        surveyService.publishSurvey("TEAM", teamId, surveyId);
        assertThat(reloadTargetCount(surveyId)).as("公開時点の母集団は2名").isEqualTo(2);

        // 公開後に 3 名加入し、1 名が退会する（母集団は 2 → 4 に変化する）。
        for (int i = 0; i < 3; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42fl") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }
        Long leaver = members.get(0);
        txTemplate.executeWithoutResult(s -> em.createNativeQuery(
                        "UPDATE memberships SET left_at = NOW() WHERE user_id = :uid AND scope_id = :sid")
                .setParameter("uid", leaver)
                .setParameter("sid", teamId)
                .executeUpdate());

        assertThat(reloadTargetCount(surveyId))
                .as("公開時の値（2）で固定されており、増減後の母集団（4）に追随しないこと")
                .isEqualTo(2);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-5: 空・0件 — 母集団0名でも例外にならず 0 が記録される
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-5) 母集団0名のスコープでも公開でき target_count は 0 になる")
    void ac5_母集団0名でも公開できる() {
        Long orgId = txTemplate.execute(s -> insertOrganization(uniq("c42eo")));
        Long surveyId = createPublishableSurvey("ORGANIZATION", orgId, DistributionMode.ALL, false);

        surveyService.publishSurvey("ORGANIZATION", orgId, surveyId);

        assertThat(reloadTargetCount(surveyId)).as("0名でも例外にならず 0 が記録されること").isZero();
        assertThat(reloadStatus(surveyId)).isEqualTo(SurveyStatus.PUBLISHED);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-6: 応援者トグルが母集団の人数に反映される
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-6) include_supporters の設定が母集団人数に反映される")
    void ac6_応援者トグルが人数へ反映される() {
        // 同一構成（MEMBER 2 名 + SUPPORTER 1 名）の組織を 2 つ作り、トグルだけを変えて比較する。
        Long orgExcl = seedOrgWithMembersAndSupporter(2, 1);
        Long orgIncl = seedOrgWithMembersAndSupporter(2, 1);

        Long exclSurvey = createPublishableSurvey("ORGANIZATION", orgExcl, DistributionMode.ALL, false);
        surveyService.publishSurvey("ORGANIZATION", orgExcl, exclSurvey);

        Long inclSurvey = createPublishableSurvey("ORGANIZATION", orgIncl, DistributionMode.ALL, true);
        surveyService.publishSurvey("ORGANIZATION", orgIncl, inclSurvey);

        assertThat(reloadTargetCount(exclSurvey))
                .as("includeSupporters=false は応援者を除外し MEMBER 2 名のみ").isEqualTo(2);
        assertThat(reloadTargetCount(inclSurvey))
                .as("includeSupporters=true は応援者を含め 3 名").isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-7: 境界 — 未公開（DRAFT）のうちは書かれない
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-7) DRAFT のうちは target_count が書かれない（作成しただけでは 0）")
    void ac7_未公開のうちは書かれない() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42dt")));
        for (int i = 0; i < 4; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42dm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }

        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.ALL, false);

        assertThat(reloadStatus(surveyId)).isEqualTo(SurveyStatus.DRAFT);
        assertThat(reloadTargetCount(surveyId))
                .as("公開していないので母集団（4名）は記録されないこと").isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-8: 途中失敗 — 公開が失敗すれば target_count も保存されない（同一トランザクション）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-8) 公開が失敗した場合 target_count も status も保存されない")
    void ac8_公開失敗時は対象人数も保存されない() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42lt")));
        for (int i = 0; i < 3; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42lm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }
        // 設問 0 件 → publishSurvey は NO_QUESTIONS で失敗する。
        Long surveyId = txTemplate.execute(s -> surveyRepository.save(SurveyEntity.builder()
                .scopeType("TEAM").scopeId(teamId).title("CMP042 公開失敗")
                .status(SurveyStatus.DRAFT).distributionMode(DistributionMode.ALL)
                .createdBy(1L).build()).getId());

        assertThatThrownBy(() -> surveyService.publishSurvey("TEAM", teamId, surveyId))
                .isInstanceOf(BusinessException.class);

        assertThat(reloadStatus(surveyId)).as("status は DRAFT のまま").isEqualTo(SurveyStatus.DRAFT);
        assertThat(reloadTargetCount(surveyId))
                .as("公開が失敗した以上、対象人数も書かれていないこと").isZero();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-9: シリーズ比較の回答率が全員配信でも正しい分母で算出される
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-9) シリーズ比較の回答率が全員配信でも 0.0% にならない")
    void ac9_シリーズ比較の回答率が正しい分母で出る() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42st")));
        List<Long> members = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42sm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
            members.add(uid);
        }
        Long adminId = txTemplate.execute(s -> insertUser(uniq("c42sa") + "@example.com"));
        txTemplate.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        });
        // 母集団は MEMBER 4 名 + ADMIN 1 名 = 5 名。

        String seriesId = uniq("c42sr");
        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.ALL, false, seriesId);
        Long questionId = firstQuestionId(surveyId);
        surveyService.publishSurvey("TEAM", teamId, surveyId);

        // 1 名が回答 → 回答率 1/5 = 20.0%
        txTemplate.executeWithoutResult(s -> {
            responseRepository.save(SurveyResponseEntity.builder()
                    .surveyId(surveyId).questionId(questionId).userId(members.get(0))
                    .textResponse("はい").build());
            SurveyEntity e = surveyRepository.findById(surveyId).orElseThrow();
            e.incrementResponseCount();
            surveyRepository.save(e);
        });

        SurveyComparisonResponse comparison = surveySeriesService.compareSeries(seriesId, adminId);
        assertThat(comparison.surveys()).hasSize(1);
        SurveyComparisonResponse.SurveySummary summary = comparison.surveys().get(0);
        assertThat(summary.targetCount()).as("分母が母集団 5 名であること").isEqualTo(5);
        assertThat(summary.responseRate())
                .as("全員配信でも 0.0%% の嘘が出ないこと").isEqualTo(20.0);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-10: 公開時に数える母集団と、結果閲覧時に数える母集団の定義が一致する
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(AC-10) 公開時のカウントと結果閲覧時の母集団定義が同一データで一致する")
    void ac10_公開時と結果閲覧時の母集団定義が一致する() {
        // 応援者を混ぜ、SUPPORTER 除外規約まで含めて両者が同じ集合を見ていることを突き合わせる。
        Long orgId = seedOrgWithMembersAndSupporter(3, 2);
        Long creatorId = txTemplate.execute(s -> insertUser(uniq("c42uc") + "@example.com"));
        txTemplate.executeWithoutResult(s ->
                MembershipTestHelper.insertMembership(em, creatorId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER));

        Long surveyId = createPublishableSurvey("ORGANIZATION", orgId, DistributionMode.ALL, false, null, creatorId);
        surveyService.publishSurvey("ORGANIZATION", orgId, surveyId);

        // 公開直後・メンバー増減なしの同一データで突き合わせる。
        List<RespondentResponse> respondents = surveyResultService.getRespondents(surveyId, creatorId);

        assertThat(reloadTargetCount(surveyId))
                .as("公開時カウント（分母）と結果閲覧時の母集団件数が一致すること")
                .isEqualTo(respondents.size());
        assertThat(reloadTargetCount(surveyId))
                .as("SUPPORTER 除外規約も両者で一致し MEMBER 3 名 + 作成者 1 名 = 4 名であること")
                .isEqualTo(4);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-11: 性能 — 公開時の母集団カウントがループ内クエリにならない
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 公開処理が用いる母集団カウント（{@link SurveyUniverseResolver#countUniverseUserIds}）を
     * 直接計測する。{@code publishSurvey} 全体を計測しないのは、公開が AFTER_COMMIT の
     * 非同期リスナー（通知 fan-out）を起こし、SessionFactory 全体で共有される
     * {@link Statistics} に別スレッドの SQL が混入して計測が非決定になるためである。
     * N+1 の危険は「母集団の人数だけクエリが増える」ことにあり、その一点をここで固定する。
     */
    @Test
    @DisplayName("(AC-11) 母集団カウントが N+1 にならない（人数を変えてもSQL数一定）")
    void ac11_母集団カウントがN1にならない() {
        Long smallTeamId = seedTeamWithMembers(2);
        Long largeTeamId = seedTeamWithMembers(20);
        Long smallSurveyId = createPublishableSurvey("TEAM", smallTeamId, DistributionMode.ALL, false);
        Long largeSurveyId = createPublishableSurvey("TEAM", largeTeamId, DistributionMode.ALL, false);
        SurveyEntity smallSurvey = txTemplate.execute(s -> surveyRepository.findById(smallSurveyId).orElseThrow());
        SurveyEntity largeSurvey = txTemplate.execute(s -> surveyRepository.findById(largeSurveyId).orElseThrow());

        // 初回はメタデータ準備等でクエリ本数が揺れるためウォームアップしてから計測する。
        universeResolver.countUniverseUserIds(smallSurvey);
        universeResolver.countUniverseUserIds(largeSurvey);

        Statistics stats = statisticsCleared();
        assertThat(universeResolver.countUniverseUserIds(smallSurvey)).isEqualTo(2);
        long smallStatements = stats.getPrepareStatementCount();

        stats.clear();
        assertThat(universeResolver.countUniverseUserIds(largeSurvey)).isEqualTo(20);
        long largeStatements = stats.getPrepareStatementCount();

        assertThat(largeStatements)
                .as("2名（%d 本）と 20名（%d 本）で発行 SQL 数が一定であること（N+1 禁止）",
                        smallStatements, largeStatements)
                .isEqualTo(smallStatements);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-12: 意図的な非対称の固定（マスター御裁可 2026-08-15）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 未回答者一覧は「今いる人」で都度算出する。分母（スナップショット）との食い違いは
     * マスターの明示的な裁可であり、催促という実務に合わせた割り切りである。
     * これを「分母に合わせて固定」してしまう改変が入ったら本テストが落ちる。
     */
    @Test
    @DisplayName("(AC-12)[番人] 未回答者一覧は今いる人で都度算出され、公開後の加入者も現れる")
    void ac12_未回答者一覧は都度算出のまま() {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42at")));
        for (int i = 0; i < 2; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42am") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }
        Long creatorId = txTemplate.execute(s -> insertUser(uniq("c42ac") + "@example.com"));
        txTemplate.executeWithoutResult(s ->
                MembershipTestHelper.insertMembership(em, creatorId, ScopeType.TEAM, teamId, RoleKind.MEMBER));

        Long surveyId = createPublishableSurvey("TEAM", teamId, DistributionMode.ALL, false, null, creatorId);
        surveyService.publishSurvey("TEAM", teamId, surveyId);
        assertThat(reloadTargetCount(surveyId)).as("公開時点の母集団は3名").isEqualTo(3);

        Long latecomerId = txTemplate.execute(s -> insertUser(uniq("c42al") + "@example.com"));
        txTemplate.executeWithoutResult(s ->
                MembershipTestHelper.insertMembership(em, latecomerId, ScopeType.TEAM, teamId, RoleKind.MEMBER));

        List<RespondentResponse> respondents = surveyResultService.getRespondents(surveyId, creatorId);

        assertThat(respondents).extracting(RespondentResponse::getUserId)
                .as("公開後に加入した人も未回答者一覧に現れること（都度算出・御裁可2）")
                .contains(latecomerId);
        assertThat(respondents)
                .as("一覧は今いる人（4名）で算出されること").hasSize(4);
        assertThat(reloadTargetCount(surveyId))
                .as("一方で分母はスナップショット（3名）のまま動かないこと").isEqualTo(3);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // フィクスチャ（test profile は ddl-auto=create + flyway 無効のため手動 seed 必須）
    // ═══════════════════════════════════════════════════════════════════════

    private Long seedTeamWithMembers(int memberCount) {
        Long teamId = txTemplate.execute(s -> insertTeam(uniq("c42zt")));
        for (int i = 0; i < memberCount; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42zm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER));
        }
        return teamId;
    }

    private Long seedOrgWithMembersAndSupporter(int memberCount, int supporterCount) {
        Long orgId = txTemplate.execute(s -> insertOrganization(uniq("c42po")));
        for (int i = 0; i < memberCount; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42pm") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER));
        }
        for (int i = 0; i < supporterCount; i++) {
            Long uid = txTemplate.execute(s -> insertUser(uniq("c42ps") + "@example.com"));
            txTemplate.executeWithoutResult(s ->
                    MembershipTestHelper.insertMembership(em, uid, ScopeType.ORGANIZATION, orgId, RoleKind.SUPPORTER));
        }
        return orgId;
    }

    private Long createPublishableSurvey(String scopeType, Long scopeId, DistributionMode mode,
                                         boolean includeSupporters) {
        return createPublishableSurvey(scopeType, scopeId, mode, includeSupporters, null, 1L);
    }

    private Long createPublishableSurvey(String scopeType, Long scopeId, DistributionMode mode,
                                         boolean includeSupporters, String seriesId) {
        return createPublishableSurvey(scopeType, scopeId, mode, includeSupporters, seriesId, 1L);
    }

    private Long createPublishableSurvey(String scopeType, Long scopeId, DistributionMode mode,
                                         boolean includeSupporters, String seriesId, Long createdBy) {
        return txTemplate.execute(s -> {
            SurveyEntity saved = surveyRepository.save(SurveyEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .title("CMP042 アンケート")
                    .status(SurveyStatus.DRAFT)
                    .distributionMode(mode)
                    .includeSupporters(includeSupporters)
                    .seriesId(seriesId)
                    .createdBy(createdBy)
                    .build());
            questionRepository.save(SurveyQuestionEntity.builder()
                    .surveyId(saved.getId())
                    .questionType(QuestionType.FREE_TEXT)
                    .questionText("ご意見をどうぞ")
                    .isRequired(false)
                    .displayOrder(1)
                    .build());
            return saved.getId();
        });
    }

    private Long firstQuestionId(Long surveyId) {
        return txTemplate.execute(s ->
                questionRepository.findBySurveyIdOrderByDisplayOrderAsc(surveyId).get(0).getId());
    }

    private int reloadTargetCount(Long surveyId) {
        return txTemplate.execute(s ->
                surveyRepository.findById(surveyId).orElseThrow().getTargetCount());
    }

    private SurveyStatus reloadStatus(Long surveyId) {
        return txTemplate.execute(s ->
                surveyRepository.findById(surveyId).orElseThrow().getStatus());
    }

    private Statistics statisticsCleared() {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        return stats;
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMP042', 'テスト', 'CMP042 テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", "CMP042 " + slug)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", "CMP042 " + slug)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
