package com.mannschaft.app.match;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.domain.TeamSide;
import com.mannschaft.app.match.entity.MatchAttachmentEntity;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.entity.MatchEventEntity;
import com.mannschaft.app.match.repository.MatchAttachmentRepository;
import com.mannschaft.app.match.repository.MatchEventRepository;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」— match（試合記録 F08.10）ドメインの認可契約テスト。
 *
 * <p><b>本テストの位置づけ: 穴を塞ぐためではなく「健全な二段の帰属チェーンを回帰固定する」ため。</b>
 * 偵察の結果、match ドメインには実穴が見つからなかった（本 PR でも新たな穴は発見していない）。
 * よって本テストは <b>将来のリファクタが黙って帰属チェーンを壊すことを機械的に禁じる番人</b>である。</p>
 *
 * <h3>固定する二段の帰属チェーン</h3>
 * <ol>
 *   <li><b>第一段（テナントゲート）</b>: {@code MatchService#getMatchOrThrow(matchId, organizationId)} が
 *       {@code findByIdAndOrganizationIdAndDeletedAtIsNull} でパスの {@code orgId} に帰属する match だけを返す。
 *       不在・越境・削除済みはすべて 404（{@code MATCH_001}）で存在を漏らさない。</li>
 *   <li><b>第二段（親子帰属）</b>: 子リソース（イベント・添付）は<b>子 ID 直引きの結果を必ず親 match_id と照合</b>する
 *       （{@code MatchEventService#getEventInMatchOrThrow} /
 *       {@code MatchAttachmentService#getAttachmentInMatchOrThrow}）。
 *       不一致は 404（{@code MATCH_002} / {@code MATCH_031}）。
 *       連鎖参照（{@code linked_event_id}）も同一 match 帰属を検証する（{@code MATCH_022}）。</li>
 * </ol>
 *
 * <h3>第二段の非空虚性をどう担保したか（最重要）</h3>
 * <p>「第二段を検証しているつもりで第一段しか通っていない」テストを避けるため、第二段のケースは
 * <b>攻撃者が URL の match に対して正当な権限を持ち、かつ URL の match が正しいテナントに属している</b>
 * 状況で組み立てている。とくに主役は <b>同一組織 orgA 内の別チーム（teamA2）の ADMIN が、
 * 自分の試合（matchA2）の URL に他チームの試合（matchA1）の子 ID を差し込む</b>ケースである。
 * この構図では:</p>
 * <ul>
 *   <li>第一段（{@code getMatchOrThrow(matchA2, orgA)}）は<b>通る</b>（matchA2 は orgA の試合）。</li>
 *   <li>権限判定（{@code assertCanRecordTimeline(adminA2, matchA2)}）も<b>通る</b>（teamA2 の ADMIN）。</li>
 *   <li>唯一 <b>第二段（親子 match_id 照合）だけ</b>が遮断している。</li>
 * </ul>
 * <p>したがって第二段の照合を外せば当該ケースは <b>200/204 になって落ちる</b>。
 * 組織まで越える cross-org 版も併記しているが、そちらは第一段でも弾ける可能性があるため
 * 「第二段の番人」としては上記の同一組織版が本命である（各テストの Javadoc に明示した）。</p>
 *
 * <h3>期待ステータスの根拠（机上で決めず実挙動に合わせた）</h3>
 * <p>{@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に登録済みの {@code MATCH_*} を実読した結果:</p>
 * <ul>
 *   <li>{@code MATCH_001}（試合不在/越境/削除済み）→ <b>404</b></li>
 *   <li>{@code MATCH_002}（イベント不在/親子不一致）→ <b>404</b></li>
 *   <li>{@code MATCH_022}（連鎖先が別 match）→ <b>404</b></li>
 *   <li>{@code MATCH_031}（添付不在/親子不一致）→ <b>404</b></li>
 *   <li>{@code MATCH_010}（操作権限なし）→ <b>403</b></li>
 * </ul>
 * <p>いずれも {@code Severity} フォールバック（500 に落ちる未登録コード）ではなく明示登録済みのため、
 * 別途進行中の「未登録コードの 500 是正」とは衝突しない。
 * {@code @PreAuthorize} 失敗は {@code CommonErrorCode.COMMON_002} → <b>403</b>。</p>
 *
 * <p>金型: {@code RecruitmentNoShowScopeContractIT} / {@code SocialAnnouncementScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
// JUnit 5 の @EnabledIf は @Inherited ではないため派生クラスでも再宣言が必須（忘れると全スキップ＝偽緑）。
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("match ドメイン 認可契約テスト（裏目付・二段の帰属チェーンの回帰固定）")
class MatchScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しない match ID（実在オラクル封じの対照群）。 */
    private static final UUID ABSENT_MATCH_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    /** 実在しないイベント ID（実在オラクル封じの対照群）。 */
    private static final UUID ABSENT_EVENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");
    /** 実在しない添付 ID（実在オラクル封じの対照群）。 */
    private static final UUID ABSENT_ATTACHMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000003");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private MatchEventRepository matchEventRepository;

    @Autowired
    private MatchAttachmentRepository matchAttachmentRepository;

    @PersistenceContext
    private EntityManager em;

    /** テナント A（正当なテナント）。 */
    private Long orgAId;
    /** テナント B（越境元テナント）。 */
    private Long orgBId;

    /** orgA のチーム 1（主役チーム）。 */
    private Long teamA1Id;
    /** orgA のチーム 2（<b>同一テナント内の別チーム</b>＝第二段の非空虚性を作る要）。 */
    private Long teamA2Id;
    /** orgB のチーム。 */
    private Long teamBId;

    /** teamA1 の ADMIN（matchA1 の正当な記録者・作成者）。 */
    private Long adminA1Id;
    /** teamA1 の非管理者メンバー（閲覧はできるが記録・編集はできない）。 */
    private Long memberA1Id;
    /** teamA2 の ADMIN（同一テナント内の別チーム管理者＝第二段の攻撃者役）。 */
    private Long adminA2Id;
    /** teamB の ADMIN（別テナントの正当な管理者＝第一段の攻撃者役）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;

    /** orgA / teamA1 の試合（守られる側）。 */
    private UUID matchA1Id;
    /** orgA / teamA2 の試合（第二段の攻撃者が正当に操作できる試合）。 */
    private UUID matchA2Id;
    /** orgB / teamB の試合（第一段の攻撃者が正当に操作できる試合）。 */
    private UUID matchBId;

    /** matchA1 のイベント（守られる側）。 */
    private UUID eventA1Id;
    /** matchA2 のイベント（同一テナントの別試合のイベント）。 */
    private UUID eventA2Id;
    /** matchB のイベント（別テナントのイベント）。 */
    private UUID eventBId;

    /** matchA1 の添付（守られる側）。 */
    private UUID attachA1Id;
    /** matchB の添付（別テナントの添付）。 */
    private UUID attachBId;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("MATCHAUTHZ 組織A");
        orgBId = insertOrganization("MATCHAUTHZ 組織B");

        teamA1Id = insertTeam("MATCHAUTHZ チームA1");
        teamA2Id = insertTeam("MATCHAUTHZ チームA2");
        teamBId = insertTeam("MATCHAUTHZ チームB");

        adminA1Id = insertUser("matchauthz-admin-a1@example.com");
        memberA1Id = insertUser("matchauthz-member-a1@example.com");
        adminA2Id = insertUser("matchauthz-admin-a2@example.com");
        adminBId = insertUser("matchauthz-admin-b@example.com");
        outsiderId = insertUser("matchauthz-outsider@example.com");

        // isAdminOrAbove（user_roles）と isMember（memberships）は別系統のため、
        // ADMIN 役にも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminA1Id, ScopeType.TEAM, teamA1Id, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminA1Id, "ADMIN", teamA1Id, null);
        MembershipTestHelper.insertMembership(em, memberA1Id, ScopeType.TEAM, teamA1Id, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminA2Id, ScopeType.TEAM, teamA2Id, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminA2Id, "ADMIN", teamA2Id, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        // outsiderId はどこにも所属させない。

        // 相手チームは未登録（opponentTeamId=null）にする。登録相手にすると相手チーム ADMIN も
        // 共同記録の記録者・閲覧者になり、遮断ケースの因果が曖昧になるため。
        matchA1Id = insertMatch(orgAId, teamA1Id, adminA1Id, "MATCHAUTHZ 会場A1");
        matchA2Id = insertMatch(orgAId, teamA2Id, adminA2Id, "MATCHAUTHZ 会場A2");
        matchBId = insertMatch(orgBId, teamBId, adminBId, "MATCHAUTHZ 会場B");

        eventA1Id = insertEvent(matchA1Id, teamA1Id, "A1原本メモ");
        eventA2Id = insertEvent(matchA2Id, teamA2Id, "A2原本メモ");
        eventBId = insertEvent(matchBId, teamBId, "B原本メモ");

        attachA1Id = insertAttachment(matchA1Id, adminA1Id, "match/" + orgAId + "/" + matchA1Id + "/a1");
        attachBId = insertAttachment(matchBId, adminBId, "match/" + orgBId + "/" + matchBId + "/b");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 第一段（テナントゲート）— /organizations/{orgId}/teams/{teamId}/matches/{matchId}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 第一段（テナントゲート）: getMatchOrThrow(matchId, orgId)")
    class TenantGate {

        /** AC-M1-1: 正当な管理者の試合詳細取得は 200（非回帰）。 */
        @Test
        @DisplayName("AC-M1-1 正当ADMINの試合詳細取得は200（非回帰）")
        void ac_m1_1_正当ADMINの試合詳細は200() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchA1Id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(matchA1Id.toString()));
        }

        /**
         * AC-M1-2: <b>他組織の matchId を自組織 URL に差し込むと 404（第一段の核心・読み取り）。</b>
         *
         * <p><b>非空虚性</b>: 攻撃者 adminB は matchB の関係者（teamB のメンバー）なので
         * {@code assertCanView}（F00 可視性）は<b>通過する</b>。可視性判定は
         * {@code MatchVisibilityResolver} が「関係する scope のメンバーか」だけを見ており
         * <b>テナントを見ない</b>ため、ここで遮断しているのは {@code getMatchOrThrow(matchB, orgA)}
         * の第一段テナントゲートだけである。第一段を外すと 200 で他組織の試合詳細が読める。</p>
         */
        @Test
        @DisplayName("AC-M1-2 他組織のmatchIdを自組織URLに差し込む閲覧は404（第一段のみが遮断）")
        void ac_m1_2_越境matchIdの閲覧は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));
        }

        /**
         * AC-M1-3: <b>他組織の matchId を自組織 URL に差し込んだ更新は 404 かつ DB が書き換わらない
         * （第一段の核心・書き込み）。</b>
         *
         * <p><b>非空虚性</b>: 攻撃者 adminB は matchB の作成者かつ teamB の ADMIN であり
         * {@code assertCanEditMeta} は<b>通過する</b>。遮断しているのは
         * {@code getMatchOrThrow(matchB, orgA)} の第一段だけ。第一段を外せば venue が書き換わる。</p>
         */
        @Test
        @DisplayName("AC-M1-3 越境matchIdのメタ更新は404かつDBは不変（第一段のみが遮断）")
        void ac_m1_3_越境メタ更新は404かつ不変() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("越境で書き換えた会場"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));

            em.flush();
            em.clear();

            MatchEntity untouched = matchRepository.findById(matchBId).orElseThrow();
            assertThat(untouched.getVenue())
                    .as("越境のメタ更新で orgB の試合が書き換えられてはならない")
                    .isEqualTo("MATCHAUTHZ 会場B");
        }

        /** AC-M1-4: 正当な管理者のメタ更新は 200 かつ DB に反映される（非回帰）。 */
        @Test
        @DisplayName("AC-M1-4 正当ADMINのメタ更新は200かつDB反映（非回帰）")
        void ac_m1_4_正当メタ更新は200() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("正当に更新した会場"))))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(matchRepository.findById(matchA1Id).orElseThrow().getVenue())
                    .as("正当な管理者のメタ更新は従来どおり反映されること")
                    .isEqualTo("正当に更新した会場");
        }

        /**
         * AC-M1-5: 権限不足のメンバー（非管理者）のメタ更新は 403 かつ DB が書き換わらない。
         *
         * <p><b>非空虚性</b>: memberA1 は teamA1 のメンバーであり第一段（テナント）は通過する。
         * 遮断しているのは {@code assertCanEditMeta}（作成者/記録係/主体チーム ADMIN のみ）。</p>
         */
        @Test
        @DisplayName("AC-M1-5 権限不足メンバーのメタ更新は403かつDBは不変")
        void ac_m1_5_権限不足メンバーのメタ更新は403() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("権限外で書き換えた会場"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));

            em.flush();
            em.clear();

            assertThat(matchRepository.findById(matchA1Id).orElseThrow().getVenue())
                    .as("権限不足のメタ更新で試合が書き換えられてはならない")
                    .isEqualTo("MATCHAUTHZ 会場A1");
        }

        /** AC-M1-6: 部外者のメタ更新は 403（在籍そのものが無い場合）。 */
        @Test
        @DisplayName("AC-M1-6 部外者のメタ更新は403")
        void ac_m1_6_部外者のメタ更新は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody("部外者が書き換えた会場"))))
                    .andExpect(status().isForbidden());
        }

        /**
         * AC-M1-7: <b>越境の実在 matchId と そもそも存在しない matchId が同一応答</b>（実在オラクル封じ）。
         *
         * <p>片方が 404 でもう片方が 403 だったり、エラーコードが違ったりすると、
         * 応答差分から「その matchId が実在するか」が判別できてしまう。</p>
         */
        @Test
        @DisplayName("AC-M1-7 越境matchIdと不在matchIdは同一応答（実在オラクル封じ）")
        void ac_m1_7_越境と不在は同一応答() throws Exception {
            setAuth(adminBId);

            String crossTenantBody = mockMvc.perform(
                            patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                                    orgAId, teamA1Id, matchBId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateBody("x"))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String absentBody = mockMvc.perform(
                            patch("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                                    orgAId, teamA1Id, ABSENT_MATCH_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateBody("x"))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossTenantBody)
                    .as("越境した実在IDと不在IDの応答本文は完全一致でなければならない（実在オラクル封じ）")
                    .isEqualTo(absentBody);
        }

        /**
         * AC-M1-8: 越境 matchId の論理削除は 404 かつ実際に削除されていない。
         *
         * <p><b>非空虚性</b>: adminB は matchB の作成者・teamB ADMIN ゆえ
         * {@code assertCanEditMeta} は通過する。第一段だけが遮断している。</p>
         */
        @Test
        @DisplayName("AC-M1-8 越境matchIdの削除は404かつ試合は残存（第一段のみが遮断）")
        void ac_m1_8_越境削除は404かつ残存() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/teams/{teamId}/matches/{matchId}",
                            orgAId, teamA1Id, matchBId))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();

            assertThat(matchRepository.findById(matchBId))
                    .as("越境の削除で orgB の試合が論理削除されてはならない（@SQLRestriction ゆえ削除済みなら empty）")
                    .isPresent();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 第二段（イベントの親子帰属）— /organizations/{orgId}/matches/{matchId}/events/{eventId}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 第二段（イベントの親子帰属）: getEventInMatchOrThrow(matchId, eventId)")
    class EventOwnershipChain {

        /** AC-M2-1: 正当な管理者のイベント更新は 200 かつ DB に反映される（非回帰）。 */
        @Test
        @DisplayName("AC-M2-1 正当ADMINのイベント更新は200かつDB反映（非回帰）")
        void ac_m2_1_正当イベント更新は200() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA1Id, eventA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("正当に更新したメモ", null))))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findById(eventA1Id).orElseThrow().getNote())
                    .as("正当な記録者のイベント更新は従来どおり反映されること")
                    .isEqualTo("正当に更新したメモ");
        }

        /**
         * AC-M2-2: <b>【第二段の核心】同一組織の別試合の eventId を自試合 URL に差し込むと 404。</b>
         *
         * <p><b>非空虚性（最重要）</b>: 攻撃者 adminA2 は teamA2 の ADMIN であり、
         * URL の matchA2 は orgA に属する自分の試合である。したがって</p>
         * <ul>
         *   <li>第一段 {@code getMatchOrThrow(matchA2, orgA)} → <b>通過</b>（同一テナント）</li>
         *   <li>権限 {@code assertCanRecordTimeline(adminA2, matchA2)} → <b>通過</b>（共同記録・teamA2 ADMIN）</li>
         * </ul>
         * <p>唯一 {@code getEventInMatchOrThrow(matchA2, eventA1)} の
         * {@code matchId.equals(event.getMatchId())} だけが遮断している。
         * この一行を外せば本ケースは 200 になり、teamA1 のイベントが teamA2 の管理者に書き換えられる。</p>
         */
        @Test
        @DisplayName("AC-M2-2 【第二段の核心】同一組織の別試合のeventIdを差し込む更新は404")
        void ac_m2_2_同一組織の別試合eventIdの更新は404() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA2Id, eventA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("越境で書き換えたメモ", null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_002.getCode()));
        }

        /**
         * AC-M2-3: 第二段で遮断されたとき、対象イベントが DB 上で書き換わっていないこと。
         *
         * <p>ステータスだけでは「例外は出たが副作用は残った」を検知できないため、
         * {@code em.flush()/clear()} で永続化コンテキストを捨てて実 DB から読み直す。</p>
         */
        @Test
        @DisplayName("AC-M2-3 【第二段の核心】遮断時に別試合のイベントがDB上で書き換わっていない")
        void ac_m2_3_第二段遮断時にイベントは不変() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA2Id, eventA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("越境で書き換えたメモ", null))))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();

            MatchEventEntity untouched = matchEventRepository.findById(eventA1Id).orElseThrow();
            assertThat(untouched.getNote())
                    .as("越境の更新で matchA1 のイベントが書き換えられてはならない")
                    .isEqualTo("A1原本メモ");
            assertThat(untouched.getMatchId())
                    .as("親 match の付け替えも起きてはならない")
                    .isEqualTo(matchA1Id);
        }

        /**
         * AC-M2-4: <b>【第二段の核心】同一組織の別試合の eventId を差し込んだ削除は 404 かつイベントは残る。</b>
         *
         * <p><b>非空虚性</b>: 削除経路は Controller に事前認可が無く、
         * {@code MatchEventService#delete} が「第一段 → 第二段 → 権限」の順に検証する。
         * adminA2 は matchA2 の記録権限を持つため、第二段を外せば
         * {@code assertCanRecordTimeline} も通過して <b>他チームのイベントが実際に消える</b>。</p>
         */
        @Test
        @DisplayName("AC-M2-4 【第二段の核心】同一組織の別試合のeventIdを差し込む削除は404かつイベント残存")
        void ac_m2_4_同一組織の別試合eventIdの削除は404() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA2Id, eventA1Id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_002.getCode()));

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findById(eventA1Id))
                    .as("越境の削除で matchA1 のイベントが消えてはならない")
                    .isPresent();
        }

        /**
         * AC-M2-5: 他組織の eventId を自組織・自試合 URL に差し込む更新は 404（第二段・越境版）。
         *
         * <p><b>非空虚性</b>: 第一段（matchA1 は orgA）も権限（adminA1 は teamA1 ADMIN）も通過し、
         * 第二段だけが遮断する。ただし本ケースは組織も跨いでいるため、
         * 「第二段の番人」としての厳密さは AC-M2-2/3/4（同一組織版）が本命である。</p>
         */
        @Test
        @DisplayName("AC-M2-5 他組織のeventIdを自試合URLに差し込む更新は404かつイベントは不変")
        void ac_m2_5_他組織eventIdの更新は404() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA1Id, eventBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("越境で書き換えたメモ", null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_002.getCode()));

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findById(eventBId).orElseThrow().getNote())
                    .as("越境の更新で orgB のイベントが書き換えられてはならない")
                    .isEqualTo("B原本メモ");
        }

        /** AC-M2-6: 越境の実在 eventId と 不在 eventId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-M2-6 越境eventIdと不在eventIdは同一応答（実在オラクル封じ）")
        void ac_m2_6_越境eventIdと不在eventIdは同一応答() throws Exception {
            setAuth(adminA1Id);

            String crossBody = mockMvc.perform(
                            patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                                    orgAId, matchA1Id, eventBId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(eventBody("x", null))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String absentBody = mockMvc.perform(
                            patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                                    orgAId, matchA1Id, ABSENT_EVENT_ID)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(eventBody("x", null))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossBody)
                    .as("越境した実在eventIdと不在eventIdの応答本文は完全一致でなければならない")
                    .isEqualTo(absentBody);
        }

        /**
         * AC-M2-7: 権限不足のメンバーは自試合の正当な eventId でも更新できない（403）。
         *
         * <p><b>非空虚性</b>: 第一段・第二段の帰属はすべて正しい。
         * 遮断しているのは Controller の {@code assertCanRecordTimeline}（共同記録は ADMIN/DEPUTY のみ）。</p>
         */
        @Test
        @DisplayName("AC-M2-7 権限不足メンバーのイベント更新は403かつDBは不変")
        void ac_m2_7_権限不足メンバーのイベント更新は403() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA1Id, eventA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("権限外で書き換えたメモ", null))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findById(eventA1Id).orElseThrow().getNote())
                    .as("権限不足の更新でイベントが書き換えられてはならない")
                    .isEqualTo("A1原本メモ");
        }

        /** AC-M2-8: 部外者のイベント更新は 403。 */
        @Test
        @DisplayName("AC-M2-8 部外者のイベント更新は403")
        void ac_m2_8_部外者のイベント更新は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/organizations/{orgId}/matches/{matchId}/events/{eventId}",
                            orgAId, matchA1Id, eventA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("部外者が書き換えたメモ", null))))
                    .andExpect(status().isForbidden());
        }

        /** AC-M2-9: 正当な管理者のイベント記録は 201 かつ DB に 1 件増える（非回帰）。 */
        @Test
        @DisplayName("AC-M2-9 正当ADMINのイベント記録は201かつDBに反映（非回帰）")
        void ac_m2_9_正当イベント記録は201() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("新規記録メモ", null))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchA1Id))
                    .as("正当な記録者のイベント記録は従来どおり保存されること")
                    .hasSize(2);
        }

        /**
         * AC-M2-10: <b>連鎖参照（linked_event_id）の帰属検証</b> — 同一組織の別試合のイベントを
         * 連鎖先に指定すると 404 かつイベントは作られない。
         *
         * <p><b>非空虚性</b>: 第一段も権限も通過し、遮断しているのは
         * {@code validateLinkedEvent} の {@code matchId.equals(linked.getMatchId())} のみ。
         * これは「第二段」と同種の親子帰属検証が<b>ボディ由来の ID</b> にも効いていることの固定である。</p>
         */
        @Test
        @DisplayName("AC-M2-10 他試合のlinkedEventIdを指定した記録は404かつイベントは作られない")
        void ac_m2_10_他試合のlinkedEventIdは404() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchA1Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("連鎖越境メモ", eventA2Id))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_022.getCode()));

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchA1Id))
                    .as("連鎖帰属違反ではイベントが作られてはならない（1件のまま）")
                    .hasSize(1);
        }

        /**
         * AC-M2-11: 他組織 matchId へのイベント記録は 404 かつ 1 件も作られない（第一段・書き込み）。
         *
         * <p><b>非空虚性</b>: adminB は matchB の記録権限を持つため、
         * 遮断しているのは {@code getMatchOrThrow(matchB, orgA)} の第一段だけ。</p>
         */
        @Test
        @DisplayName("AC-M2-11 越境matchIdへのイベント記録は404かつ1件も作られない")
        void ac_m2_11_越境matchIdへのイベント記録は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(eventBody("越境記録メモ", null))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));

            em.flush();
            em.clear();

            assertThat(matchEventRepository.findByMatchIdOrderByPeriodAscMinuteAscSortSeqAsc(matchBId))
                    .as("越境の記録で orgB の試合にイベントが増えてはならない（1件のまま）")
                    .hasSize(1);
        }

        /**
         * AC-M2-12: 越境 matchId のタイムライン取得は 404（第一段・読み取り）。
         *
         * <p><b>非空虚性</b>: 可視性（{@code assertCanView}）はテナントを見ないため
         * adminB は matchB を「見てよい」判定になる。遮断は第一段だけ。</p>
         */
        @Test
        @DisplayName("AC-M2-12 越境matchIdのタイムライン取得は404（第一段のみが遮断）")
        void ac_m2_12_越境タイムライン取得は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));
        }

        /** AC-M2-13: 正当メンバーのタイムライン取得は 200 で自試合のイベントのみが返る（漏洩なし）。 */
        @Test
        @DisplayName("AC-M2-13 正当メンバーのタイムライン取得は200で自試合分のみ（他試合・他組織は混ざらない）")
        void ac_m2_13_正当タイムライン取得は自試合分のみ() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchA1Id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.events.length()").value(1))
                    .andExpect(jsonPath("$.data.events[0].id").value(eventA1Id.toString()))
                    .andExpect(jsonPath("$.data.events[0].matchId").value(matchA1Id.toString()));
        }

        /** AC-M2-14: 部外者のタイムライン取得は 404（可視性 fail-closed・存在を漏らさない）。 */
        @Test
        @DisplayName("AC-M2-14 部外者のタイムライン取得は404（可視性fail-closed）")
        void ac_m2_14_部外者のタイムライン取得は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/matches/{matchId}/events",
                            orgAId, matchA1Id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 第二段（添付の親子帰属）— /organizations/{orgId}/matches/{matchId}/attachments/{attachmentId}
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 第二段（添付の親子帰属）: getAttachmentInMatchOrThrow(matchId, attachmentId)")
    class AttachmentOwnershipChain {

        /**
         * AC-M3-1: <b>【第二段の核心・添付版】同一組織の別試合の attachmentId を
         * 自試合 URL に差し込んだ削除は 404 かつ添付は残る。</b>
         *
         * <p><b>非空虚性</b>: adminA2 は matchA2 の記録権限を持ち、matchA2 は orgA に属する。
         * よって第一段・権限とも通過し、{@code getAttachmentInMatchOrThrow} の
         * {@code matchId.equals(attachment.getMatchId())} だけが遮断している。</p>
         */
        @Test
        @DisplayName("AC-M3-1 【第二段の核心】同一組織の別試合のattachmentIdを差し込む削除は404かつ添付残存")
        void ac_m3_1_同一組織の別試合attachmentIdの削除は404() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/matches/{matchId}/attachments/{attachmentId}",
                            orgAId, matchA2Id, attachA1Id))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_031.getCode()));

            em.flush();
            em.clear();

            assertThat(matchAttachmentRepository.findById(attachA1Id))
                    .as("越境の削除で matchA1 の添付が消えてはならない")
                    .isPresent();
        }

        /**
         * AC-M3-2: 他組織の attachmentId を自試合 URL に差し込んだ削除は 404 かつ添付は残る。
         *
         * <p>第一段（matchA1 は orgA）も権限（adminA1 は teamA1 ADMIN）も通過し、第二段だけが遮断する。</p>
         */
        @Test
        @DisplayName("AC-M3-2 他組織のattachmentIdを自試合URLに差し込む削除は404かつ添付残存")
        void ac_m3_2_他組織attachmentIdの削除は404() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/matches/{matchId}/attachments/{attachmentId}",
                            orgAId, matchA1Id, attachBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_031.getCode()));

            em.flush();
            em.clear();

            assertThat(matchAttachmentRepository.findById(attachBId))
                    .as("越境の削除で orgB の添付が消えてはならない")
                    .isPresent();
        }

        /**
         * AC-M3-3: 権限不足のメンバーは自試合の正当な attachmentId でも削除できない（403）。
         *
         * <p>帰属はすべて正しく、{@code assertCanRecordTimeline} だけが遮断する。</p>
         */
        @Test
        @DisplayName("AC-M3-3 権限不足メンバーの添付削除は403かつ添付残存")
        void ac_m3_3_権限不足メンバーの添付削除は403() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(delete("/api/v1/organizations/{orgId}/matches/{matchId}/attachments/{attachmentId}",
                            orgAId, matchA1Id, attachA1Id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));

            em.flush();
            em.clear();

            assertThat(matchAttachmentRepository.findById(attachA1Id))
                    .as("権限不足の削除で添付が消えてはならない")
                    .isPresent();
        }

        /**
         * AC-M3-4: 正当メンバーの添付一覧は 200 で自試合分のみが返る（非回帰＋漏洩なし）。
         *
         * <p>遮断ケースばかりで「何をやっても 404」になっていないことの裏取りも兼ねる。</p>
         */
        @Test
        @DisplayName("AC-M3-4 正当メンバーの添付一覧は200で自試合分のみ（非回帰・漏洩なし）")
        void ac_m3_4_正当添付一覧は自試合分のみ() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/matches/{matchId}/attachments",
                            orgAId, matchA1Id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(attachA1Id.toString()));
        }

        /**
         * AC-M3-5: 越境 matchId の添付一覧は 404（第一段・読み取り）。
         *
         * <p>可視性はテナントを見ないため adminB は matchB を見てよい判定になる。遮断は第一段だけ。</p>
         */
        @Test
        @DisplayName("AC-M3-5 越境matchIdの添付一覧は404（第一段のみが遮断）")
        void ac_m3_5_越境添付一覧は404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/matches/{matchId}/attachments",
                            orgAId, matchBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_001.getCode()));
        }

        /**
         * AC-M3-6: 越境の実在 attachmentId と 不在 attachmentId が同一応答（実在オラクル封じ）。
         *
         * <p>ダウンロード URL 発行 EP で検証する。どちらのケースも第二段で弾かれるため
         * ストレージ（presign）には一切到達しない。</p>
         */
        @Test
        @DisplayName("AC-M3-6 越境attachmentIdと不在attachmentIdは同一応答（実在オラクル封じ）")
        void ac_m3_6_越境attachmentIdと不在は同一応答() throws Exception {
            setAuth(adminA1Id);

            String crossBody = mockMvc.perform(
                            get("/api/v1/organizations/{orgId}/matches/{matchId}"
                                            + "/attachments/{attachmentId}/download-url",
                                    orgAId, matchA1Id, attachBId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            String absentBody = mockMvc.perform(
                            get("/api/v1/organizations/{orgId}/matches/{matchId}"
                                            + "/attachments/{attachmentId}/download-url",
                                    orgAId, matchA1Id, ABSENT_ATTACHMENT_ID))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossBody)
                    .as("越境した実在attachmentIdと不在IDの応答本文は完全一致でなければならない")
                    .isEqualTo(absentBody);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. チームスコープ EP（@PreAuthorize 第二防御 + Service 第一防御）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. チームスコープEP（@PreAuthorize + Service第一防御）")
    class TeamScopedEndpoints {

        /** AC-M4-1: 正当メンバーの試合一覧は 200 で自チーム分のみ（非回帰）。 */
        @Test
        @DisplayName("AC-M4-1 正当メンバーの試合一覧は200で自チーム分のみ（非回帰）")
        void ac_m4_1_正当試合一覧は200() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", orgAId, teamA1Id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(matchA1Id.toString()));
        }

        /** AC-M4-2: 部外者の試合一覧は 403（{@code @PreAuthorize} の第二防御）。 */
        @Test
        @DisplayName("AC-M4-2 部外者の試合一覧は403")
        void ac_m4_2_部外者の試合一覧は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", orgAId, teamA1Id))
                    .andExpect(status().isForbidden());
        }

        /** AC-M4-3: 同一組織の別チーム ADMIN が当該チーム URL を叩いても 403（横方向の越境遮断）。 */
        @Test
        @DisplayName("AC-M4-3 同一組織の別チームADMINが当該チームURLを叩くと403")
        void ac_m4_3_別チームADMINの試合一覧は403() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", orgAId, teamA1Id))
                    .andExpect(status().isForbidden());
        }

        /**
         * AC-M4-4: 自チーム URL に他組織の orgId を載せても他組織の試合は返らない（一覧のテナント絞り）。
         *
         * <p><b>非空虚性</b>: {@code @PreAuthorize} は teamA1 のメンバーシップしか見ないため<b>通過する</b>。
         * ここで 0 件を保証しているのは {@code findTeamMatches} の
         * {@code m.organizationId = :orgId} 条件だけである。この条件が落ちれば matchA1 が返る。</p>
         */
        @Test
        @DisplayName("AC-M4-4 他組織のorgIdを載せた自チーム試合一覧は0件（リポジトリのテナント絞りのみが効いている）")
        void ac_m4_4_他組織orgIdの試合一覧は0件() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/matches", orgBId, teamA1Id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        /** AC-M4-5: 正当メンバーのチーム統計は 200（非回帰）。 */
        @Test
        @DisplayName("AC-M4-5 正当メンバーのチーム統計は200（非回帰）")
        void ac_m4_5_正当チーム統計は200() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", orgAId, teamA1Id))
                    .andExpect(status().isOk());
        }

        /** AC-M4-6: 部外者のチーム統計は 403。 */
        @Test
        @DisplayName("AC-M4-6 部外者のチーム統計は403")
        void ac_m4_6_部外者のチーム統計は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", orgAId, teamA1Id))
                    .andExpect(status().isForbidden());
        }

        /** AC-M4-7: 同一組織の別チーム ADMIN のチーム統計は 403。 */
        @Test
        @DisplayName("AC-M4-7 同一組織の別チームADMINのチーム統計は403")
        void ac_m4_7_別チームADMINのチーム統計は403() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/teams/{teamId}/match-stats", orgAId, teamA1Id))
                    .andExpect(status().isForbidden());
        }

        /** AC-M4-8: 本人の個人キャリア統計（チーム横断）は 200（非回帰）。 */
        @Test
        @DisplayName("AC-M4-8 本人の個人キャリア統計は200（非回帰）")
        void ac_m4_8_本人の個人統計は200() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/match-stats", orgAId, memberA1Id))
                    .andExpect(status().isOk());
        }

        /**
         * AC-M4-9: 他人の個人キャリア統計（チーム横断）は 403。
         *
         * <p>同一チームの管理者であっても、チーム横断の個人統計は本人限定（02 §F.1）。</p>
         */
        @Test
        @DisplayName("AC-M4-9 他人の個人キャリア統計（チーム横断）は403")
        void ac_m4_9_他人の個人統計は403() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/match-stats", orgAId, memberA1Id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));
        }

        /**
         * AC-M4-10: 非管理者メンバーが同僚の team スコープ個人統計を覗くと 403
         *（公開設定 OFF ＋ 閲覧者が非管理者）。
         */
        @Test
        @DisplayName("AC-M4-10 非管理者メンバーが同僚のteamスコープ個人統計を覗くと403")
        void ac_m4_10_非管理者の他者teamスコープ個人統計は403() throws Exception {
            setAuth(memberA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                            orgAId, adminA1Id, teamA1Id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));
        }

        /** AC-M4-11: チーム ADMIN は同一チーム所属者の team スコープ個人統計を閲覧できる（非回帰）。 */
        @Test
        @DisplayName("AC-M4-11 チームADMINは同一チーム所属者のteamスコープ個人統計を閲覧できる（非回帰）")
        void ac_m4_11_ADMINの他者teamスコープ個人統計は200() throws Exception {
            setAuth(adminA1Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                            orgAId, memberA1Id, teamA1Id))
                    .andExpect(status().isOk());
        }

        /** AC-M4-12: 別チーム ADMIN は当該チーム所属者の team スコープ個人統計を閲覧できない（403）。 */
        @Test
        @DisplayName("AC-M4-12 別チームADMINは当該チーム所属者のteamスコープ個人統計を閲覧できない")
        void ac_m4_12_別チームADMINの他者teamスコープ個人統計は403() throws Exception {
            setAuth(adminA2Id);
            mockMvc.perform(get("/api/v1/organizations/{orgId}/users/{userId}/teams/{teamId}/match-stats",
                            orgAId, memberA1Id, teamA1Id))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(MatchErrorCode.MATCH_010.getCode()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 試合メタ更新リクエストボディ（{@code venue} 以外は現状維持相当の値を明示する）。 */
    private Map<String, Object> updateBody(String venue) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("homeAway", HomeAway.HOME.name());
        body.put("opponentTeamId", null);
        body.put("opponentName", "MATCHAUTHZ 対戦相手");
        body.put("venue", venue);
        body.put("durationMinutes", 90);
        return body;
    }

    /** タイムラインイベントの記録/更新リクエストボディ（サッカーの GOAL・HOME サイド）。 */
    private Map<String, Object> eventBody(String note, UUID linkedEventId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minute", 10);
        body.put("period", PeriodType.FIRST_HALF.name());
        body.put("eventType", MatchEventType.GOAL.name());
        body.put("teamSide", TeamSide.HOME.name());
        body.put("playerName", "MATCHAUTHZ 選手");
        body.put("note", note);
        body.put("linkedEventId", linkedEventId != null ? linkedEventId.toString() : null);
        body.put("sortSeq", 0);
        return body;
    }

    /**
     * 共同記録モード（{@code hasScorekeeper=false}）のサッカー試合を 1 件作る。
     *
     * <p>相手チームは未登録（{@code opponentTeamId=null}）にする。登録相手にすると相手チームの
     * ADMIN も記録権限・可視性を得てしまい、遮断ケースの因果（何が遮断したのか）が曖昧になるため。</p>
     */
    private UUID insertMatch(Long organizationId, Long teamId, Long createdBy, String venue) {
        return matchRepository.save(MatchEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .sport(Sport.SOCCER)
                .stateModel(StateModel.CONTINUOUS_TIME)
                .kind(MatchKind.FRIENDLY)
                .homeAway(HomeAway.HOME)
                .opponentTeamId(null)
                .opponentName("MATCHAUTHZ 対戦相手")
                .kickoffAt(LocalDateTime.now().minusDays(1))
                .venue(venue)
                .durationMinutes(90)
                .status(MatchStatus.IN_PROGRESS)
                .hasScorekeeper(false)
                .createdBy(createdBy)
                .build()).getId();
    }

    /** 指定試合に GOAL イベントを 1 件作る（{@code recorded_by_team_id} は主体チーム名義）。 */
    private UUID insertEvent(UUID matchId, Long recordedByTeamId, String note) {
        return matchEventRepository.save(MatchEventEntity.builder()
                .matchId(matchId)
                .minute(10)
                .period(PeriodType.FIRST_HALF)
                .eventType(MatchEventType.GOAL)
                .teamSide(TeamSide.HOME)
                .playerName("MATCHAUTHZ 選手")
                .note(note)
                .recordedByTeamId(recordedByTeamId)
                .sortSeq(0)
                .build()).getId();
    }

    /** 指定試合に局面写真添付を 1 件作る。 */
    private UUID insertAttachment(UUID matchId, Long createdBy, String fileKey) {
        return matchAttachmentRepository.save(MatchAttachmentEntity.builder()
                .matchId(matchId)
                .fileKey(fileKey)
                .originalFilename("position.png")
                .contentType("image/png")
                .fileSize(1024L)
                .createdBy(createdBy)
                .build()).getId();
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
                                + "VALUES (:email, 'MATCHAUTHZ', 'テスト', 'MATCHAUTHZ テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('mtch-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('mtcho-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
