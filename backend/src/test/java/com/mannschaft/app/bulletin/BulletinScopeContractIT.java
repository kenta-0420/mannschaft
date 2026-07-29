package com.mannschaft.app.bulletin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.bulletin.entity.BulletinArchiveFolderEntity;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinArchiveFolderRepository;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.membership.domain.RoleKind;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」— bulletin（掲示板 F05.1）ドメインのスコープ帰属 認可契約テスト。
 *
 * <p><b>本テストの位置づけ</b>: bulletin は {@code @PreAuthorize} をひとつも持たないため、
 * 第一陣の静的監査（{@code AuthzControllerGuardArchTest}）の網の外にあった。スコープ付き EP の
 * 帰属検証は {@code BulletinThreadService#findThreadOrThrow(scopeType, scopeId, threadId)} /
 * {@code BulletinCategoryService#findCategoryOrThrow} /
 * {@code BulletinReplyRepository#findByIdAndThreadId} /
 * {@code BulletinArchiveFolderRepository#findByScopeForUpdate} という
 * <b>スコープ済み finder</b> に一元化されており、本テストはこの健全な状態を
 * <b>実 HTTP + 実 MySQL 経路で回帰固定</b>することを主目的とする。</p>
 *
 * <p><b>本テストが炙り出した実穴（1 件）</b>:
 * {@code GET /api/v1/{scopeType}/{scopeId}/bulletin/threads?categoryId=...} は
 * {@code BulletinThreadService#listThreadsByCategory} で「URL スコープへの所属」だけを
 * {@code accessGuard.checkMembership} で検証し、続く一覧取得は
 * <b>categoryId 単独の finder</b>（{@code findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc}）
 * だった。すなわちカテゴリ側の帰属検証が無く、自スコープの URL に他スコープの categoryId を
 * 差し込むだけで他テナントのスレッド（タイトル・本文・投稿者）を読めた。
 * 同じ経路をスコープ付き EP と Global EP（{@code GlobalBulletinThreadController#listThreads}）の
 * 双方が共有していた。近隣の {@code createThread}（{@code findCategoryOrThrow} で検証済）および
 * 村スコープ版（{@code findByScopeVillageIdAndCategoryId...} とスコープ済みクエリ）が
 * 正しい型を持っており、<b>この 1 メソッドだけが規律を破っていた</b>。
 * 根治は近隣の型に揃え、(1) {@code findCategoryOrThrow} による帰属検証と
 * (2) スコープ条件付き finder への差し替え、の二重で塞いだ（AC-B2 / AC-B4）。</p>
 *
 * <p><b>期待ステータスの根拠</b>: {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に
 * 登録済みの {@code BULLETIN_*} のうち本テストが依存するのは次のとおり（すべて明示登録・実挙動確認済）:</p>
 * <ul>
 *   <li>{@code BULLETIN_001}（CATEGORY_NOT_FOUND）→ <b>404</b></li>
 *   <li>{@code BULLETIN_002}（THREAD_NOT_FOUND）→ <b>404</b></li>
 *   <li>{@code BULLETIN_003}（REPLY_NOT_FOUND）→ <b>404</b></li>
 *   <li>{@code BULLETIN_016}（ARCHIVE_FOLDER_NOT_FOUND）→ <b>404</b></li>
 *   <li>{@code BULLETIN_020}（ARCHIVE_FOLDER_SCOPE_MISMATCH）→ <b>409</b></li>
 *   <li>認可拒否は {@code CommonErrorCode.COMMON_002} が <b>403</b> で明示登録</li>
 * </ul>
 * <p>{@code BULLETIN_012}（PARENT_REPLY_MISMATCH）は {@code Severity.ERROR} かつ未登録のため
 * 500 に落ちる既知の欠陥があるが、別 PR で是正中のため本テストでは一切依存しない。</p>
 *
 * <p><b>対象外</b>: 親スコープを URL に持たない Global 系 EP
 * （{@code GlobalBulletin*Controller} / {@code BulletinReactionController} /
 * {@code BulletinAttachmentController}）は逆引き認可という別設計であり、
 * {@code BulletinAccessGuard} が村スコープを意図的に素通しして
 * {@code PostingIdentityService} に委ねる構造の実効性検討を要するため、別任務とする。</p>
 *
 * <p>金型: {@code RecruitmentNoShowScopeContractIT} / {@code SocialAnnouncementScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("bulletin 掲示板 認可契約テスト（裏目付・スコープ帰属の越境封鎖）")
class BulletinScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しないカテゴリ ID（実在オラクル封じの対照群）。 */
    private static final long ABSENT_CATEGORY_ID = 999_999_901L;
    /** 実在しないスレッド ID（実在オラクル封じの対照群）。 */
    private static final long ABSENT_THREAD_ID = 999_999_902L;
    /** 実在しない返信 ID（実在オラクル封じの対照群）。 */
    private static final long ABSENT_REPLY_ID = 999_999_903L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BulletinThreadRepository threadRepository;

    @Autowired
    private BulletinCategoryRepository categoryRepository;

    @Autowired
    private BulletinReplyRepository replyRepository;

    @Autowired
    private BulletinArchiveFolderRepository folderRepository;

    @PersistenceContext
    private EntityManager em;

    // --- スコープ ---
    private Long teamAId;
    private Long teamBId;
    private Long orgCId;
    private Long orgDId;

    // --- 人物 ---
    /** teamA の ADMIN（管理操作の正当な実行者）。 */
    private Long adminAId;
    /** teamA の非管理者メンバー（閲覧・投稿はできるが管理操作は 403）。 */
    private Long memberAId;
    /** teamA と teamB の<b>両方</b>に所属し teamB の返信を書いた者（本人性ゲートを無効化して帰属検証だけを検査するための人物）。 */
    private Long crossAuthorId;
    /** teamB の ADMIN（越境攻撃者役）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;
    /** orgC の ADMIN。 */
    private Long adminCId;
    /** 個人スコープ（PERSONAL）の所有者。{@code scope_id = このユーザーの userId}。 */
    private Long personalOwnerId;

    // --- カテゴリ ---
    private Long catAId;
    private Long catBId;
    private Long catCId;
    private Long catDId;

    // --- スレッド ---
    /** teamA のスレッド（投稿者 = memberA）。 */
    private Long threadAId;
    /** teamA のスレッド（投稿者 = adminA。本人削除の非回帰用）。 */
    private Long threadA2Id;
    /** teamB のスレッド（越境 threadId として teamA の URL に差し込む主役）。 */
    private Long threadBId;
    /** orgC のスレッド。 */
    private Long threadCId;
    /** orgD のスレッド（ORGANIZATION 側の越境 ID）。 */
    private Long threadDId;
    /** teamA のアーカイブ済みスレッド（保管庫 folderA 所属）。 */
    private Long archivedAId;
    /** teamB のアーカイブ済みスレッド（保管庫 folderB 所属。越境 ID の主役）。 */
    private Long archivedBId;
    /** 個人スコープのスレッド（{@code scope_id = personalOwnerId}）。本人以外に見えてはならない。 */
    private Long personalThreadId;

    // --- 返信 ---
    /** threadA の返信（投稿者 = memberA）。 */
    private Long replyAId;
    /** threadB の返信（投稿者 = crossAuthor）。crossAuthor から見れば「自分の投稿」なので本人性ゲートは通る。 */
    private Long replyBId;

    // --- 保管庫フォルダ ---
    private UUID folderAId;
    private UUID folderBId;

    /** teamB の、他テナントに漏れてはならない秘匿本文（応答本文への混入検査に使う）。 */
    private static final String SECRET_BODY_B = "BULAUTHZ teamB 秘匿本文";

    /** orgD の、他テナントに漏れてはならない秘匿本文（ORGANIZATION スコープ版の混入検査に使う）。 */
    private static final String SECRET_BODY_D = "BULAUTHZ orgD 秘匿本文";

    /** 個人スコープの、本人以外に漏れてはならない秘匿本文。 */
    private static final String SECRET_BODY_PERSONAL = "BULAUTHZ 個人 秘匿本文";

    /**
     * 実在しない村 ID。
     *
     * <p>村の正当系（グローバル経路）が入口ゲートに阻まれず村ドメインの認可判定まで到達することを
     * 確認するために使う。村を 1 件も作らずに「村ドメインのエラーコードが返ること」で到達を判定するため、
     * 村テーブル群のフィクスチャを組まずに済む。</p>
     */
    private static final UUID ABSENT_VILLAGE_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ff");

    /** 村ドメインの「村が見つかりません」エラーコード（到達判定に使う）。 */
    private static final String VILLAGE_NOT_FOUND_CODE = "VILLAGE_001";

    /** 認可拒否の共通エラーコード（入口ゲート・本人性ゲートの判定に使う）。 */
    private static final String FORBIDDEN_CODE = "COMMON_002";

    @BeforeEach
    void setUp() {
        // roles は Flyway 無効（test プロファイルは Entity 由来 schema）で空のため、
        // priority を含めて明示 seed する。priority が無いと hasRoleOrAbove が常に false になり
        // 正当系（スレッド作成 201）が 403 に化けて「遮断できている」と誤読される。
        seedRoles();

        teamAId = insertTeam("BULAUTHZ チームA");
        teamBId = insertTeam("BULAUTHZ チームB");
        orgCId = insertOrganization("BULAUTHZ 組織C");
        orgDId = insertOrganization("BULAUTHZ 組織D");

        adminAId = insertUser("bulauthz-admin-a@example.com");
        memberAId = insertUser("bulauthz-member-a@example.com");
        crossAuthorId = insertUser("bulauthz-cross-author@example.com");
        adminBId = insertUser("bulauthz-admin-b@example.com");
        outsiderId = insertUser("bulauthz-outsider@example.com");
        adminCId = insertUser("bulauthz-admin-c@example.com");
        personalOwnerId = insertUser("bulauthz-personal-owner@example.com");

        // isAdmin（user_roles）と isMember（memberships）は別系統のため、ADMIN 役にも memberships を張る。
        MembershipTestHelper.insertMembership(em, adminAId, membershipScope("TEAM"), teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, membershipScope("TEAM"), teamAId, RoleKind.MEMBER);
        // crossAuthor は teamA / teamB の双方に所属する（越境 replyId 差し込み時に本人性ゲートを通過させる）。
        MembershipTestHelper.insertMembership(em, crossAuthorId, membershipScope("TEAM"), teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, crossAuthorId, membershipScope("TEAM"), teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, membershipScope("TEAM"), teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminCId, membershipScope("ORGANIZATION"), orgCId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminCId, "ADMIN", null, orgCId);
        // outsiderId はどこにも所属させない。

        catAId = saveCategory(ScopeType.TEAM, teamAId, "BULAUTHZ カテゴリA", adminAId);
        catBId = saveCategory(ScopeType.TEAM, teamBId, "BULAUTHZ カテゴリB", adminBId);
        catCId = saveCategory(ScopeType.ORGANIZATION, orgCId, "BULAUTHZ カテゴリC", adminCId);
        catDId = saveCategory(ScopeType.ORGANIZATION, orgDId, "BULAUTHZ カテゴリD", adminCId);

        threadAId = saveThread(ScopeType.TEAM, teamAId, catAId, memberAId,
                "BULAUTHZ teamA スレッド", "teamA 本文", false);
        threadA2Id = saveThread(ScopeType.TEAM, teamAId, catAId, adminAId,
                "BULAUTHZ teamA スレッド2", "teamA 本文2", false);
        threadBId = saveThread(ScopeType.TEAM, teamBId, catBId, adminBId,
                "BULAUTHZ teamB スレッド", SECRET_BODY_B, false);
        threadCId = saveThread(ScopeType.ORGANIZATION, orgCId, catCId, adminCId,
                "BULAUTHZ orgC スレッド", "orgC 本文", false);
        threadDId = saveThread(ScopeType.ORGANIZATION, orgDId, catDId, adminCId,
                "BULAUTHZ orgD スレッド", SECRET_BODY_D, false);

        folderAId = saveFolder(ScopeType.TEAM, teamAId, "BULAUTHZ 保管庫A", adminAId);
        folderBId = saveFolder(ScopeType.TEAM, teamBId, "BULAUTHZ 保管庫B", adminBId);

        archivedAId = saveArchivedThread(ScopeType.TEAM, teamAId, adminAId, "BULAUTHZ teamA 保管済", folderAId);
        archivedBId = saveArchivedThread(ScopeType.TEAM, teamBId, adminBId, "BULAUTHZ teamB 保管済", folderBId);

        // 個人スコープ（scope_id = 所有者の userId）。カテゴリは付けない（未分類）。
        personalThreadId = saveThread(ScopeType.PERSONAL, personalOwnerId, null, personalOwnerId,
                "BULAUTHZ 個人スレッド", SECRET_BODY_PERSONAL, false);

        replyAId = saveReply(threadAId, memberAId, "teamA 返信");
        replyBId = saveReply(threadBId, crossAuthorId, "teamB 返信");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /threads?categoryId=（★実穴★ 越境カテゴリ ID による他テナント読取）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /{scopeType}/{scopeId}/bulletin/threads?categoryId=（カテゴリ絞り込み一覧）")
    class ListThreadsByCategory {

        /**
         * AC-B1: 正当メンバーの自スコープ categoryId 指定は 200 で自スコープ分だけ返る（非回帰）。
         *
         * <p>過剰遮断していないことの裏取り。帰属検証を追加した結果「正当な絞り込みまで 404 になる」
         * 退行を起こしていないことを固定する。</p>
         */
        @Test
        @DisplayName("AC-B1 正当メンバーの自スコープcategoryId指定は200で自スコープ分のみ（非回帰）")
        void ac_b1_正当categoryId指定は200() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                                    .param("categoryId", String.valueOf(catAId)))
                    .andExpect(status().isOk())
                    // catA を持つのは threadA / threadA2 の 2 件のみ
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].scopeId").value(teamAId))
                    .andExpect(jsonPath("$.data[1].scopeId").value(teamAId))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_B);
        }

        /**
         * AC-B2（★実穴の回帰固定★）: 自スコープの URL に他スコープの categoryId を差し込むと遮断される。
         *
         * <p><b>非空虚性</b>: 攻撃者 memberA は teamA の正当なメンバーであり
         * {@code accessGuard.checkMembership(memberA, TEAM, teamA)} を<b>通過する</b>。
         * したがって唯一の障壁はカテゴリ側の帰属検証であり、それを外すと 200 で
         * teamB のスレッド本文が返る（根治前は red）。応答本文に秘匿本文が含まれないことまで照合する。</p>
         */
        @Test
        @DisplayName("AC-B2 正当メンバーが越境categoryIdを差し込むと404（他テナント読取の封鎖）")
        void ac_b2_越境categoryIdは404() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                                    .param("categoryId", String.valueOf(catBId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("越境カテゴリ絞り込みの応答に他テナントのスレッド本文が混ざってはならない")
                    .doesNotContain(SECRET_BODY_B);
        }

        /**
         * AC-B3: 実在オラクル封じ。「越境した実在 categoryId」と「そもそも存在しない categoryId」が
         * 同一ステータス・同一応答本文で返ること。
         */
        @Test
        @DisplayName("AC-B3 越境categoryIdと不在categoryIdは同一応答（実在オラクル封じ）")
        void ac_b3_越境と不在は同一応答() throws Exception {
            setAuth(memberAId);
            String crossTenant = listByCategoryExpectingNotFound(teamAId, catBId);
            String absent = listByCategoryExpectingNotFound(teamAId, ABSENT_CATEGORY_ID);

            assertThat(crossTenant)
                    .as("越境した実在IDと不在IDの応答本文は完全一致でなければならない")
                    .isEqualTo(absent);
        }

        /**
         * AC-B4: ORGANIZATION スコープでも越境 categoryId は遮断され、orgD のスレッドが漏れないこと。
         *
         * <p><b>非空虚性</b>: adminC は orgC の正当な ADMIN であり所属ゲートを通過する。
         * カテゴリの帰属検証だけが orgD のスレッド本文の露出を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B4 組織スコープでも越境categoryIdは404で他組織のスレッドが漏れない")
        void ac_b4_組織スコープの越境categoryIdは404() throws Exception {
            setAuth(adminCId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "organizations", orgCId)
                                    .param("categoryId", String.valueOf(catDId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body)
                    .as("越境カテゴリ絞り込みの応答に orgD のスレッド本文が混ざってはならない")
                    .doesNotContain(SECRET_BODY_D);
            // 遮断された orgD のスレッドが実在していること（対象が空でオラクルが成立しない事故の防止）
            assertThat(threadRepository.findById(threadDId)).isPresent();
        }

        /** AC-B5: ORGANIZATION スコープの正当 categoryId 指定は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B5 組織スコープの正当categoryId指定は200（非回帰）")
        void ac_b5_組織スコープの正当categoryIdは200() throws Exception {
            setAuth(adminCId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "organizations", orgCId)
                            .param("categoryId", String.valueOf(catCId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(threadCId));
        }

        /**
         * AC-B6: 別テナント ADMIN が当該スコープ URL を叩くと 403。
         *
         * <p><b>非空虚性の注意</b>: これは帰属検証ではなく <b>所属ゲート</b>
         * （{@code accessGuard.checkMembership}）が先に発火するケースである。帰属検証の回帰固定
         * ではなく「所属ゲートが外れていない」ことの固定として置く。</p>
         */
        @Test
        @DisplayName("AC-B6 別テナントADMINが当該スコープURLを叩くと403（所属ゲート）")
        void ac_b6_別テナントADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                            .param("categoryId", String.valueOf(catAId)))
                    .andExpect(status().isForbidden());
        }

        /** AC-B7: categoryId 未指定の一覧にも他スコープのスレッドは混ざらない（非回帰）。 */
        @Test
        @DisplayName("AC-B7 categoryId未指定の一覧は自スコープ分のみ（混入なし）")
        void ac_b7_categoryId未指定の一覧は自スコープのみ() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId))
                    .andExpect(status().isOk())
                    // threadA / threadA2 / archivedA の 3 件（アーカイブ済みも一覧には出る仕様）
                    .andExpect(jsonPath("$.data.length()").value(3))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_B);
        }

        private String listByCategoryExpectingNotFound(Long scopeId, Long categoryId) throws Exception {
            return mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", scopeId)
                            .param("categoryId", String.valueOf(categoryId)))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /threads/{threadId}（スレッド帰属・読取）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /{scopeType}/{scopeId}/bulletin/threads/{threadId}（詳細取得）")
    class GetThread {

        /**
         * AC-B8: 越境 threadId の詳細取得は遮断される。
         *
         * <p><b>非空虚性</b>: memberA は teamA の正当メンバーなので所属ゲートを通過する。
         * 唯一の障壁は {@code findThreadOrThrow(TEAM, teamA, threadId)} のスコープ済み finder であり、
         * これを {@code findById} に退化させると 200 で teamB の本文が返る。</p>
         */
        @Test
        @DisplayName("AC-B8 越境threadIdの詳細取得は404で本文が漏れない")
        void ac_b8_越境threadIdは404() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                                    "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_B);
        }

        /** AC-B9: 越境 threadId と不在 threadId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-B9 越境threadIdと不在threadIdは同一応答（実在オラクル封じ）")
        void ac_b9_越境と不在は同一応答() throws Exception {
            setAuth(memberAId);
            String crossTenant = getThreadExpectingNotFound(threadBId);
            String absent = getThreadExpectingNotFound(ABSENT_THREAD_ID);
            assertThat(crossTenant).isEqualTo(absent);
        }

        /** AC-B10: 正当メンバーの自スコープスレッド詳細は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B10 正当メンバーの自スコープスレッド詳細は200（非回帰）")
        void ac_b10_正当スレッド詳細は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(threadAId))
                    .andExpect(jsonPath("$.data.scopeId").value(teamAId));
        }

        /** AC-B11: 部外者は 403（所属ゲート）。 */
        @Test
        @DisplayName("AC-B11 部外者のスレッド詳細取得は403")
        void ac_b11_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isForbidden());
        }

        private String getThreadExpectingNotFound(Long threadId) throws Exception {
            return mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT / DELETE /threads/{threadId}（スレッド帰属・書込）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT / DELETE /{scopeType}/{scopeId}/bulletin/threads/{threadId}（更新・削除）")
    class WriteThread {

        /**
         * AC-B12: 正当 ADMIN が越境 threadId を更新しようとすると遮断される。
         *
         * <p><b>非空虚性</b>: adminA は teamA の ADMIN であり、所属ゲートも
         * 「投稿者本人 or ADMIN」ゲート（{@code isAdminOrAbove} は<b>URL のスコープ</b>で評価される）も
         * <b>両方通過する</b>。したがって帰属検証を外すと teamB のスレッド本文が書き換わる。</p>
         */
        @Test
        @DisplayName("AC-B12 正当ADMINが越境threadIdを更新しようとすると404")
        void ac_b12_越境threadIdの更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateThreadBody("改竄タイトル", "改竄本文"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));
        }

        /** AC-B13: 遮断時に他スコープのスレッドが DB 上で書き換わっていないこと。 */
        @Test
        @DisplayName("AC-B13 遮断時に他スコープのスレッドがDB上で書き換わっていない")
        void ac_b13_遮断時にDB不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateThreadBody("改竄タイトル", "改竄本文"))))
                    .andExpect(status().isNotFound());

            em.flush();
            em.clear();

            BulletinThreadEntity untouched = threadRepository.findById(threadBId).orElseThrow();
            assertThat(untouched.getBody())
                    .as("越境更新で teamB のスレッド本文が書き換えられてはならない")
                    .isEqualTo(SECRET_BODY_B);
            assertThat(untouched.getTitle()).isEqualTo("BULAUTHZ teamB スレッド");
        }

        /** AC-B14: 正当 ADMIN の自スコープスレッド更新は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B14 正当ADMINの自スコープ更新は200（非回帰）")
        void ac_b14_正当更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateThreadBody("更新後", "更新後本文"))))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadAId).orElseThrow().getTitle())
                    .as("正当スコープの更新は従来どおり反映されること")
                    .isEqualTo("更新後");
        }

        /**
         * AC-B15: 正当 ADMIN が越境 threadId を削除しようとすると遮断され、DB 上で生存していること。
         *
         * <p><b>非空虚性</b>: 削除の権限ゲート {@code requireManageContent} も URL のスコープ（teamA）で
         * 評価されるため adminA は通過する。帰属検証だけが teamB のスレッド消失を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B15 正当ADMINが越境threadIdを削除しようとすると404かつDB上生存")
        void ac_b15_越境threadIdの削除は404かつ生存() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadBId))
                    .as("越境削除で teamB のスレッドが論理削除されてはならない")
                    .isPresent();
        }

        /** AC-B16: 投稿者本人（かつ ADMIN）の自スコープ削除は 204（非回帰）。 */
        @Test
        @DisplayName("AC-B16 投稿者本人の自スコープ削除は204（非回帰）")
        void ac_b16_正当削除は204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadA2Id))
                    .andExpect(status().isNoContent());

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadA2Id))
                    .as("正当スコープの削除は従来どおり論理削除されること（@SQLRestriction により不可視）")
                    .isEmpty();
        }

        /** AC-B17: 部外者の更新は 403（所属ゲート）。 */
        @Test
        @DisplayName("AC-B17 部外者の更新は403")
        void ac_b17_部外者の更新は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "teams", teamAId, threadAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateThreadBody("改竄", "改竄"))))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. ピン留め / ロック / アーカイブ（管理操作の帰属）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /threads/{threadId}/pin・lock・archive（管理操作）")
    class ModerateThread {

        /**
         * AC-B18: 越境 threadId のピン留めは遮断され、DB 上で変化しないこと。
         *
         * <p><b>非空虚性</b>: {@code togglePin} は
         * {@code checkMembership(URL scope)} → {@code requireManageContent(URL scope)} →
         * {@code findThreadOrThrow(URL scope, threadId)} の順で、前 2 つは adminA が teamA の
         * ADMIN であるため通過する。帰属検証が唯一の障壁。</p>
         */
        @Test
        @DisplayName("AC-B18 越境threadIdのピン留めは404かつDB不変")
        void ac_b18_越境ピン留めは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/pin",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadBId).orElseThrow().getIsPinned())
                    .as("越境ピン留めで teamB のスレッド状態が変わってはならない")
                    .isFalse();
        }

        /** AC-B19: 越境 threadId のロックは遮断され、DB 上で変化しないこと。 */
        @Test
        @DisplayName("AC-B19 越境threadIdのロックは404かつDB不変")
        void ac_b19_越境ロックは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/lock",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    // エラーコードまで照合する。ステータスだけだとパス誤記による
                    // ハンドラ不在 404 でも緑になり、テストが空虚化する。
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadBId).orElseThrow().getIsLocked())
                    .as("越境ロックで teamB のスレッドがロックされてはならない")
                    .isFalse();
        }

        /** AC-B20: 越境 threadId のアーカイブは遮断され、DB 上で変化しないこと。 */
        @Test
        @DisplayName("AC-B20 越境threadIdのアーカイブは404かつDB不変")
        void ac_b20_越境アーカイブは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/archive",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    // エラーコードまで照合する（ハンドラ不在 404 との取り違え防止）。
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(threadBId).orElseThrow().getIsArchived())
                    .as("越境アーカイブで teamB のスレッドが保管庫へ送られてはならない")
                    .isFalse();
        }

        /** AC-B21: 正当 ADMIN の自スコープピン留めは 200（非回帰）。 */
        @Test
        @DisplayName("AC-B21 正当ADMINの自スコープピン留めは200（非回帰）")
        void ac_b21_正当ピン留めは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/pin",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isPinned").value(true));
        }

        /** AC-B22: 非管理者メンバーのピン留めは 403（管理権限ゲート）。 */
        @Test
        @DisplayName("AC-B22 非管理者メンバーのピン留めは403（管理権限ゲート）")
        void ac_b22_非管理者のピン留めは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/pin",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /threads（作成時のボディ由来 categoryId の帰属）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /{scopeType}/{scopeId}/bulletin/threads（スレッド作成）")
    class CreateThread {

        /**
         * AC-B23: ボディの categoryId に他スコープの ID を指定すると遮断され、スレッドが作られないこと。
         *
         * <p><b>非空虚性</b>: memberA は teamA の正当メンバーであり所属ゲートも投稿ロールゲートも
         * 通過する。{@code findCategoryOrThrow} を外すと、他スコープのカテゴリ ID を紐づけた
         * スレッドが作成されてしまう（テナント間のデータ混線）。</p>
         */
        @Test
        @DisplayName("AC-B23 ボディの越境categoryIdは404でスレッドが作られない")
        void ac_b23_越境categoryIdの作成は404() throws Exception {
            long before = countThreadsInScope(ScopeType.TEAM, teamAId);

            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createThreadBody(catBId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(countThreadsInScope(ScopeType.TEAM, teamAId))
                    .as("遮断されたのにスレッドが作成されてはならない")
                    .isEqualTo(before);
        }

        /**
         * AC-B24: 正当メンバーの自スコープ categoryId 指定の作成は 201（非回帰）。
         *
         * <p>本ケースは作成経路全体の要（かなめ）である。ここが落ちると観点5 の遮断側 2 件も
         * 巻き添えで落ちるため、<b>失敗時に応答本文を必ず読めるようにしてある</b>
         * （ステータスだけだと原因が判らず CI を 1 往復無駄にする）。
         * 期待値は 201 のまま一切甘くしていない。</p>
         */
        @Test
        @DisplayName("AC-B24 正当メンバーの自スコープ作成は201（非回帰）")
        void ac_b24_正当作成は201() throws Exception {
            setAuth(memberAId);
            var result = mockMvc.perform(
                            post("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(createThreadBody(catAId))))
                    .andReturn();
            String responseBody = result.getResponse().getContentAsString();

            assertThat(result.getResponse().getStatus())
                    .as("スレッド作成の応答本文: %s", responseBody)
                    .isEqualTo(201);

            var data = objectMapper.readTree(responseBody).path("data");
            assertThat(data.path("scopeId").asLong()).isEqualTo(teamAId);
            assertThat(data.path("categoryId").asLong()).isEqualTo(catAId);
        }

        /** AC-B25: 作成時も越境 categoryId と不在 categoryId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-B25 作成時も越境categoryIdと不在categoryIdは同一応答")
        void ac_b25_作成時の越境と不在は同一応答() throws Exception {
            setAuth(memberAId);
            String crossTenant = createThreadExpectingNotFound(catBId);
            String absent = createThreadExpectingNotFound(ABSENT_CATEGORY_ID);
            assertThat(crossTenant).isEqualTo(absent);
        }

        private String createThreadExpectingNotFound(Long categoryId) throws Exception {
            return mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "teams", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createThreadBody(categoryId))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 返信（スレッド帰属 → 返信帰属の連鎖）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. /{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies（返信）")
    class Replies {

        /** AC-B26: 越境 threadId の返信一覧は遮断され、他スコープの返信が漏れないこと。 */
        @Test
        @DisplayName("AC-B26 越境threadIdの返信一覧は404で他スコープの返信が漏れない")
        void ac_b26_越境threadIdの返信一覧は404() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies",
                                    "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("teamB 返信");
        }

        /** AC-B27: 正当メンバーの自スレッド返信一覧は 200 で自スレッド分のみ（非回帰）。 */
        @Test
        @DisplayName("AC-B27 正当メンバーの自スレッド返信一覧は200で自スレッド分のみ（非回帰）")
        void ac_b27_正当返信一覧は200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(replyAId));
        }

        /**
         * AC-B28: 越境 threadId への返信作成は遮断され、返信行が作られないこと。
         *
         * <p><b>非空虚性</b>: memberA は teamA の正当メンバーで所属ゲートを通過する。
         * {@code findThreadOrThrow} を外すと teamB のスレッドに返信が投稿できてしまう。</p>
         */
        @Test
        @DisplayName("AC-B28 越境threadIdへの返信作成は404で返信行が作られない")
        void ac_b28_越境threadIdへの返信作成は404() throws Exception {
            long before = countRepliesOfThread(threadBId);

            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies",
                            "teams", teamAId, threadBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(replyBody("越境返信"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(countRepliesOfThread(threadBId))
                    .as("越境スレッドへ返信行が作られてはならない")
                    .isEqualTo(before);
            assertThat(threadRepository.findById(threadBId).orElseThrow().getReplyCount())
                    .as("越境スレッドの返信カウントも増えてはならない")
                    .isEqualTo(1);
        }

        /**
         * AC-B29: 自スコープ URL に越境 replyId を差し込む更新は遮断され、DB 上で本文が不変であること。
         *
         * <p><b>非空虚性（重要）</b>: 攻撃者 crossAuthor は teamA / teamB の<b>双方に所属</b>し、
         * かつ replyB の<b>投稿者本人</b>である。したがって所属ゲートも本人性ゲート
         * （{@code NOT_AUTHOR}）も通過し、{@code findByIdAndThreadId(replyId, threadId)} という
         * 親スレッド一致の帰属検証だけが書き換えを防いでいる。これを {@code findById} に
         * 退化させると 200 で他スレッドの返信が書き換わる。</p>
         */
        @Test
        @DisplayName("AC-B29 自スコープURLへの越境replyId更新は404かつDB上本文不変")
        void ac_b29_越境replyIdの更新は404() throws Exception {
            setAuth(crossAuthorId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies/{replyId}",
                            "teams", teamAId, threadAId, replyBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateReplyBody("改竄返信"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.REPLY_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(replyRepository.findById(replyBId).orElseThrow().getBody())
                    .as("越境更新で teamB の返信本文が書き換えられてはならない")
                    .isEqualTo("teamB 返信");
        }

        /**
         * AC-B30: 自スコープ URL に越境 replyId を差し込む削除は遮断され、DB 上で生存すること。
         *
         * <p><b>非空虚性</b>: adminA は teamA の ADMIN であり、削除の権限ゲート
         * {@code requireManageContent(URL scope)} を通過する。帰属検証だけが削除を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B30 自スコープURLへの越境replyId削除は404かつDB上生存")
        void ac_b30_越境replyIdの削除は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies/{replyId}",
                            "teams", teamAId, threadAId, replyBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.REPLY_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(replyRepository.findById(replyBId))
                    .as("越境削除で teamB の返信が論理削除されてはならない")
                    .isPresent();
        }

        /** AC-B31: 越境 replyId と不在 replyId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-B31 越境replyIdと不在replyIdは同一応答（実在オラクル封じ）")
        void ac_b31_越境と不在は同一応答() throws Exception {
            setAuth(crossAuthorId);
            String crossTenant = updateReplyExpectingNotFound(replyBId);
            String absent = updateReplyExpectingNotFound(ABSENT_REPLY_ID);
            assertThat(crossTenant).isEqualTo(absent);
        }

        /** AC-B32: 正当メンバーの自スレッド返信作成は 201（非回帰）。 */
        @Test
        @DisplayName("AC-B32 正当メンバーの自スレッド返信作成は201（非回帰）")
        void ac_b32_正当返信作成は201() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies",
                            "teams", teamAId, threadAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(replyBody("正当返信"))))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();

            assertThat(countRepliesOfThread(threadAId))
                    .as("正当スコープの返信作成は従来どおり反映されること")
                    .isEqualTo(2);
        }

        private String updateReplyExpectingNotFound(Long replyId) throws Exception {
            return mockMvc.perform(
                            put("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies/{replyId}",
                                    "teams", teamAId, threadAId, replyId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateReplyBody("改竄返信"))))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. カテゴリ（帰属）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. /{scopeType}/{scopeId}/bulletin/categories（カテゴリ）")
    class Categories {

        /** AC-B33: 越境 categoryId の詳細取得は遮断され、カテゴリ名が漏れないこと。 */
        @Test
        @DisplayName("AC-B33 越境categoryIdの詳細取得は404でカテゴリ名が漏れない")
        void ac_b33_越境categoryIdの詳細は404() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/categories/{categoryId}",
                                    "teams", teamAId, catBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("BULAUTHZ カテゴリB");
        }

        /**
         * AC-B34: 越境 categoryId の更新は遮断され、DB 上で不変であること。
         *
         * <p><b>非空虚性</b>: adminA は teamA の ADMIN であり
         * {@code checkMembership} / {@code requireManageContent} をいずれも通過する。
         * {@code findCategoryOrThrow} だけが他テナントのカテゴリ改竄を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B34 越境categoryIdの更新は404かつDB上不変")
        void ac_b34_越境categoryIdの更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/categories/{categoryId}",
                            "teams", teamAId, catBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCategoryBody("改竄カテゴリ"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(categoryRepository.findById(catBId).orElseThrow().getName())
                    .as("越境更新で teamB のカテゴリ名が書き換えられてはならない")
                    .isEqualTo("BULAUTHZ カテゴリB");
        }

        /**
         * AC-B35: 越境 categoryId の削除は遮断され、他テナントのカテゴリもスレッドの分類も壊れないこと。
         *
         * <p><b>非空虚性（破壊力が最大のケース）</b>: {@code deleteCategory} は
         * {@code bulkSetCategoryIdNullByCategoryId(categoryId)} で<b>配下スレッドを一括で未分類化</b>する。
         * adminA は権限ゲートを通過するため、帰属検証を外すと他テナントのカテゴリ論理削除に加え
         * teamB のスレッド分類まで一括で破壊される。カテゴリの生存とスレッドの categoryId 双方を照合する。</p>
         */
        @Test
        @DisplayName("AC-B35 越境categoryIdの削除は404かつ他テナントのスレッドが未分類化されない")
        void ac_b35_越境categoryIdの削除は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/{scopeType}/{scopeId}/bulletin/categories/{categoryId}",
                            "teams", teamAId, catBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(categoryRepository.findById(catBId))
                    .as("越境削除で teamB のカテゴリが論理削除されてはならない")
                    .isPresent();
            assertThat(threadRepository.findById(threadBId).orElseThrow().getCategoryId())
                    .as("越境削除で teamB のスレッドが未分類化されてはならない")
                    .isEqualTo(catBId);
        }

        /** AC-B36: 正当 ADMIN の自スコープカテゴリ更新は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B36 正当ADMINの自スコープカテゴリ更新は200（非回帰）")
        void ac_b36_正当カテゴリ更新は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/categories/{categoryId}",
                            "teams", teamAId, catAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateCategoryBody("更新後カテゴリ"))))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(categoryRepository.findById(catAId).orElseThrow().getName())
                    .isEqualTo("更新後カテゴリ");
        }

        /** AC-B37: カテゴリ一覧に他スコープのカテゴリが混ざらないこと（非回帰）。 */
        @Test
        @DisplayName("AC-B37 カテゴリ一覧は自スコープ分のみ（混入なし）")
        void ac_b37_カテゴリ一覧は自スコープのみ() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/categories", "teams", teamAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(catAId));
        }

        /** AC-B38: 越境 categoryId と不在 categoryId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-B38 カテゴリ詳細の越境IDと不在IDは同一応答（実在オラクル封じ）")
        void ac_b38_越境と不在は同一応答() throws Exception {
            setAuth(memberAId);
            String crossTenant = getCategoryExpectingNotFound(catBId);
            String absent = getCategoryExpectingNotFound(ABSENT_CATEGORY_ID);
            assertThat(crossTenant).isEqualTo(absent);
        }

        private String getCategoryExpectingNotFound(Long categoryId) throws Exception {
            return mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/categories/{categoryId}",
                            "teams", teamAId, categoryId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. 既読（帰属）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. /{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status（既読）")
    class ReadStatus {

        /**
         * AC-B39: 越境 threadId の既読は遮断され、既読行も既読数も増えないこと。
         *
         * <p><b>非空虚性</b>: memberA は teamA の正当メンバーで所属ゲートを通過する。
         * 帰属検証を外すと他テナントのスレッドに既読行が生成され、既読数が汚染される。</p>
         */
        @Test
        @DisplayName("AC-B39 越境threadIdの既読は404で既読行も既読数も増えない")
        void ac_b39_越境threadIdの既読は404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(countReadStatus(threadBId, memberAId))
                    .as("越境既読で teamB のスレッドに既読行が作られてはならない")
                    .isZero();
            assertThat(threadRepository.findById(threadBId).orElseThrow().getReadCount())
                    .as("越境既読で teamB のスレッドの既読数が増えてはならない")
                    .isZero();
        }

        /** AC-B40: 正当メンバーの既読は 201 で既読行が 1 件作られる（非回帰）。 */
        @Test
        @DisplayName("AC-B40 正当メンバーの既読は201（非回帰）")
        void ac_b40_正当既読は201() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "teams", teamAId, threadAId))
                    .andExpect(status().isCreated());

            em.flush();
            em.clear();

            assertThat(countReadStatus(threadAId, memberAId)).isEqualTo(1);
        }

        /** AC-B41: 越境 threadId の既読者一覧は遮断される。 */
        @Test
        @DisplayName("AC-B41 越境threadIdの既読者一覧は404")
        void ac_b41_越境threadIdの既読者一覧は404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "teams", teamAId, threadBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));
        }

        /** AC-B42: 既読の越境 threadId と不在 threadId が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-B42 既読の越境threadIdと不在threadIdは同一応答（実在オラクル封じ）")
        void ac_b42_越境と不在は同一応答() throws Exception {
            setAuth(memberAId);
            String crossTenant = markAsReadExpectingNotFound(threadBId);
            String absent = markAsReadExpectingNotFound(ABSENT_THREAD_ID);
            assertThat(crossTenant).isEqualTo(absent);
        }

        private String markAsReadExpectingNotFound(Long threadId) throws Exception {
            return mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "teams", teamAId, threadId))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. 保管庫フォルダ（帰属）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. /{scopeType}/{scopeId}/bulletin/archive（保管庫フォルダ）")
    class ArchiveFolders {

        /**
         * AC-B43: 越境 folderId の更新は遮断され、DB 上で不変であること。
         *
         * <p><b>非空虚性</b>: adminA は teamA の ADMIN であり
         * {@code checkMembership} / {@code requireManageContent} を通過する。
         * {@code findByScopeForUpdate(scopeType, scopeId)} でスコープ内フォルダだけを引き当て、
         * その中に対象が居なければ 404 とする実装だけが改竄を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B43 越境folderIdの更新は404かつDB上不変")
        void ac_b43_越境folderIdの更新は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders/{folderId}",
                            "teams", teamAId, folderBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "改竄フォルダ"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(folderRepository.findById(folderBId).orElseThrow().getName())
                    .as("越境更新で teamB の保管庫フォルダ名が書き換えられてはならない")
                    .isEqualTo("BULAUTHZ 保管庫B");
        }

        /** AC-B44: 越境 folderId の削除は遮断され、DB 上で生存すること。 */
        @Test
        @DisplayName("AC-B44 越境folderIdの削除は404かつDB上生存")
        void ac_b44_越境folderIdの削除は404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders/{folderId}",
                            "teams", teamAId, folderBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(folderRepository.findById(folderBId))
                    .as("越境削除で teamB の保管庫フォルダが論理削除されてはならない")
                    .isPresent();
            assertThat(threadRepository.findById(archivedBId).orElseThrow().getArchiveFolderId())
                    .as("越境削除で teamB のスレッドが保管庫直下へ退避されてはならない")
                    .isEqualTo(folderBId);
        }

        /**
         * AC-B45: 保管庫スレッド一覧に越境 folder_id を指定すると 409 で遮断され、
         * 他スコープのスレッドは返らないこと。
         *
         * <p><b>非空虚性の限界（正直な但し書き）</b>: この EP は
         * {@code validateFolderInScope} による明示検証に加え、取得クエリ自体が
         * {@code findByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderId} と
         * スコープ済みである。したがって明示検証を外しても<b>データは漏れず 0 件</b>になる。
         * 本ケースは「明示検証（409 契約）が外されていないこと」の固定であり、
         * 漏洩そのものの回帰固定ではない。</p>
         */
        @Test
        @DisplayName("AC-B45 保管庫一覧の越境folder_idは409で他スコープのスレッドは返らない")
        void ac_b45_越境folderIdの保管庫一覧は409() throws Exception {
            setAuth(adminAId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/archive/threads", "teams", teamAId)
                                    .param("folder_id", folderBId.toString()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain("BULAUTHZ teamB 保管済");
        }

        /**
         * AC-B46: 越境 threadId のフォルダ振り分けは遮断され、DB 上で不変であること。
         *
         * <p><b>非空虚性</b>: adminA は権限ゲートを通過し、対象 archivedB は
         * {@code is_archived=TRUE} なので {@code THREAD_NOT_ARCHIVED}（409）でも弾かれない。
         * {@code findThreadOrThrow} だけが唯一の障壁。</p>
         */
        @Test
        @DisplayName("AC-B46 越境threadIdのフォルダ振り分けは404かつDB上不変")
        void ac_b46_越境threadIdの振り分けは404() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/{scopeType}/{scopeId}/bulletin/archive/threads/{threadId}/folder",
                            "teams", teamAId, archivedBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveFolderBody(folderAId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.THREAD_NOT_FOUND.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(archivedBId).orElseThrow().getArchiveFolderId())
                    .as("越境振り分けで teamB のスレッドが teamA のフォルダへ移動してはならない")
                    .isEqualTo(folderBId);
        }

        /**
         * AC-B47: 自スレッドを越境 folderId へ振り分けようとすると 409 で遮断され、DB 上で不変であること。
         *
         * <p><b>非空虚性</b>: スレッド側の帰属は正しいため {@code findThreadOrThrow} は通過する。
         * {@code validateFolderInScope} の {@code verifyScope} だけが、他テナントのフォルダ ID を
         * 自スレッドに書き込む（＝テナント間参照の混線）ことを防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B47 自スレッドを越境folderIdへ振り分けようとすると409かつDB上不変")
        void ac_b47_越境folderIdへの振り分けは409() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/{scopeType}/{scopeId}/bulletin/archive/threads/{threadId}/folder",
                            "teams", teamAId, archivedAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveFolderBody(folderBId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH.getCode()));

            em.flush();
            em.clear();

            assertThat(threadRepository.findById(archivedAId).orElseThrow().getArchiveFolderId())
                    .as("越境フォルダへの振り分けは DB に反映されてはならない")
                    .isEqualTo(folderAId);
        }

        /** AC-B48: 正当 ADMIN の自スコープフォルダ更新は 200・保管庫一覧は自スコープ分のみ（非回帰）。 */
        @Test
        @DisplayName("AC-B48 正当ADMINの自スコープフォルダ更新は200・保管庫一覧は自スコープ分のみ（非回帰）")
        void ac_b48_正当フォルダ操作は200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders/{folderId}",
                            "teams", teamAId, folderAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "更新後フォルダ"))))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/archive/threads", "teams", teamAId)
                            .param("folder_id", folderAId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(archivedAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. スコープ種別の入口ゲート（村スコープは正規の入口へ一本化）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * スコープ付きパス経路（{@code /api/v1/{scopeType}/{scopeId}/bulletin/...}）は
     * 認可判定手段を持つスコープ種別のみを受理する。
     *
     * <p>村掲示板は村 ID（{@code scope_village_id}）を伴うグローバル経路が正規の入口であり、
     * 村 ID を伴わないこのパス形式では村側の認可判定を行えない。判定手段を持たない経路は
     * 素通しではなく拒否とし、入口を一本化する。</p>
     *
     * <p><b>非空虚性</b>: 遮断は {@code BulletinScopeIdResolver} の入口ゲート<b>のみ</b>が担う。
     * ゲートを外すと、村スコープでは所属ゲートも管理権限ゲートも実効判定を行わない
     * （ロール基盤の外にあるため）ので、いずれの EP も 200 側へ抜ける。
     * したがって「別の理由で先に弾かれているだけ」ではない。</p>
     *
     * <p>本テストは村を 1 件も作らない。ゲートは DB アクセス前に発火するため、
     * 村テーブル群のフィクスチャ無しで遮断を検査できる。</p>
     */
    @Nested
    @DisplayName("10. スコープ付きパス経路のスコープ種別ゲート（村は正規の入口へ一本化）")
    class PathScopeGate {

        /** AC-B49: スレッド一覧。5 コントローラそれぞれで遮断されることを個別に固定する。 */
        @Test
        @DisplayName("AC-B49 村スコープのスレッド一覧はスコープ付きパスでは403")
        void ac_b49_村スコープのスレッド一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads", "villages", 0))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));
        }

        /** AC-B50: スレッド詳細も同様に遮断される。 */
        @Test
        @DisplayName("AC-B50 村スコープのスレッド詳細はスコープ付きパスでは403")
        void ac_b50_村スコープのスレッド詳細は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "villages", 0, threadAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));
        }

        /** AC-B51: カテゴリ一覧（BulletinCategoryController）も同様に遮断される。 */
        @Test
        @DisplayName("AC-B51 村スコープのカテゴリ一覧はスコープ付きパスでは403")
        void ac_b51_村スコープのカテゴリ一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/categories", "villages", 0))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));
        }

        /** AC-B52: 返信一覧（BulletinReplyController）も同様に遮断される。 */
        @Test
        @DisplayName("AC-B52 村スコープの返信一覧はスコープ付きパスでは403")
        void ac_b52_村スコープの返信一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/replies",
                            "villages", 0, threadAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));
        }

        /** AC-B53: 既読マーク（BulletinReadStatusController・書込系）は遮断され既読行も作られない。 */
        @Test
        @DisplayName("AC-B53 村スコープの既読マークはスコープ付きパスでは403かつ既読行が作られない")
        void ac_b53_村スコープの既読マークは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "villages", 0, threadAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));

            em.flush();
            em.clear();

            assertThat(countReadStatus(threadAId, memberAId))
                    .as("遮断されたのに既読行が作られてはならない")
                    .isZero();
        }

        /** AC-B54: 保管庫フォルダ一覧（BulletinArchiveFolderController）も同様に遮断される。 */
        @Test
        @DisplayName("AC-B54 村スコープの保管庫フォルダ一覧はスコープ付きパスでは403")
        void ac_b54_村スコープの保管庫一覧は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders", "villages", 0))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE));
        }

        /**
         * AC-B55: 村の正当系（グローバル経路の一覧）は入口ゲートに阻まれず、
         * 村ドメインの認可判定へ到達すること。
         *
         * <p>村ドメイン固有のエラーコードが返ることをもって「到達した」と判定する。
         * 入口ゲートが誤ってグローバル経路まで塞いでいれば、代わりに認可拒否コードが返る。</p>
         */
        @Test
        @DisplayName("AC-B55 村の一覧はグローバル経路では村ドメインの認可判定へ到達する（非回帰）")
        void ac_b55_村一覧のグローバル経路は村認可へ到達() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/bulletin/threads")
                            .param("scope_type", "VILLAGE")
                            .param("scope_id", "0")
                            .param("scope_village_id", ABSENT_VILLAGE_ID.toString()))
                    .andExpect(jsonPath("$.error.code").value(VILLAGE_NOT_FOUND_CODE));
        }

        /**
         * AC-B56: 村の正当系（グローバル経路の作成）は村ドメインの主体検証へ到達すること。
         *
         * <p><b>非空虚性</b>: スレッド作成は共通ガードを呼ぶ経路上にあるため、村分岐を入れ忘れると
         * 村スレッド作成が認可拒否で全滅する。本ケースはその退行を検出する回帰ガードであり、
         * 村ドメイン固有のエラーコードが返ることで「共通ガードで止まらず村検証まで進んだ」ことを示す。</p>
         */
        @Test
        @DisplayName("AC-B56 村スレッド作成はグローバル経路で村ドメインの主体検証へ到達する（非回帰）")
        void ac_b56_村作成のグローバル経路は村検証へ到達() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/bulletin/threads")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(globalVillageCreateBody())))
                    .andExpect(jsonPath("$.error.code").value(VILLAGE_NOT_FOUND_CODE));
        }

        private Map<String, Object> globalVillageCreateBody() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("scopeType", "VILLAGE");
            payload.put("scopeId", 0);
            payload.put("scopeVillageId", ABSENT_VILLAGE_ID.toString());
            payload.put("title", "BULAUTHZ 村スレッド");
            payload.put("body", "BULAUTHZ 村本文");
            return payload;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. 個人スコープ（PERSONAL）の本人性検証
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 個人スコープは {@code scope_id} がそのまま所有者の user_id である。
     * 共通ガードが本人であることを検証し、本人以外は拒否する。
     *
     * <p><b>非空虚性</b>: 個人スコープにはメンバーシップもロールも存在しないため、
     * 本人性の判定を外すと「認証済みでありさえすれば通る」状態になり、
     * 遮断側のケースはすべて 200 側へ抜ける。他に先行して弾くゲートは無い。</p>
     */
    @Nested
    @DisplayName("11. PERSONAL スコープの本人性検証")
    class PersonalScopeOwnership {

        /** AC-B57: 他人の個人スコープのスレッド一覧は遮断される。 */
        @Test
        @DisplayName("AC-B57 他人の個人スコープのスレッド一覧は403で本文が漏れない")
        void ac_b57_他人の個人一覧は403() throws Exception {
            setAuth(outsiderId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads",
                                    "personal", personalOwnerId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_PERSONAL);
        }

        /** AC-B58: 本人の個人スコープのスレッド一覧は 200（非回帰・過剰遮断していないことの裏取り）。 */
        @Test
        @DisplayName("AC-B58 本人の個人スコープのスレッド一覧は200（非回帰）")
        void ac_b58_本人の個人一覧は200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads",
                            "personal", personalOwnerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(personalThreadId));
        }

        /** AC-B59: 他人の個人スコープのスレッド詳細は遮断され、本文が漏れない。 */
        @Test
        @DisplayName("AC-B59 他人の個人スコープのスレッド詳細は403で本文が漏れない")
        void ac_b59_他人の個人詳細は403() throws Exception {
            setAuth(outsiderId);
            String body = mockMvc.perform(
                            get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                                    "personal", personalOwnerId, personalThreadId))
                    .andExpect(status().isForbidden())
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_PERSONAL);
        }

        /** AC-B60: 本人の個人スコープのスレッド詳細は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B60 本人の個人スコープのスレッド詳細は200（非回帰）")
        void ac_b60_本人の個人詳細は200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(get("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}",
                            "personal", personalOwnerId, personalThreadId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(personalThreadId));
        }

        /** AC-B61: 他人の個人スコープへの既読マークは遮断され、既読行も作られない。 */
        @Test
        @DisplayName("AC-B61 他人の個人スコープの既読マークは403かつ既読行が作られない")
        void ac_b61_他人の個人既読は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/threads/{threadId}/read-status",
                            "personal", personalOwnerId, personalThreadId))
                    .andExpect(status().isForbidden());

            em.flush();
            em.clear();

            assertThat(countReadStatus(personalThreadId, outsiderId)).isZero();
        }

        /**
         * AC-B62: 逆引き経路（グローバル詳細）でも他人の個人スレッドは遮断される。
         *
         * <p>グローバル経路は threadId だけで叩かれ、スレッドから逆引きしたスコープで認可する。
         * 本人性の判定を共通ガードに置くことで、スコープ付きパスと逆引き経路の双方が同時に守られる。</p>
         */
        @Test
        @DisplayName("AC-B62 グローバル経路でも他人の個人スレッド詳細は403で本文が漏れない")
        void ac_b62_グローバル経路の他人個人詳細は403() throws Exception {
            setAuth(outsiderId);
            String body = mockMvc.perform(get("/api/v1/bulletin/threads/{threadId}", personalThreadId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value(FORBIDDEN_CODE))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_PERSONAL);
        }

        /** AC-B63: グローバル経路でも本人の個人スレッド詳細は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B63 グローバル経路で本人の個人スレッド詳細は200（非回帰）")
        void ac_b63_グローバル経路の本人個人詳細は200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(get("/api/v1/bulletin/threads/{threadId}", personalThreadId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(personalThreadId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. 追加被覆（グローバル経路の共有・保管庫フォルダ作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. 追加被覆")
    class AdditionalCoverage {

        /**
         * AC-B64: グローバル経路のカテゴリ絞り込みでも越境 categoryId は遮断される。
         *
         * <p>現状はスコープ付き経路とサービス層を共有しているため同じ実装で守られるが、
         * 将来グローバル側が別メソッドへ分岐したときに気づけるよう、契約として固定する。</p>
         */
        @Test
        @DisplayName("AC-B64 グローバル経路の越境categoryIdも404で他テナントのスレッドが漏れない")
        void ac_b64_グローバル経路の越境categoryIdは404() throws Exception {
            setAuth(memberAId);
            String body = mockMvc.perform(get("/api/v1/bulletin/threads")
                            .param("scope_type", "TEAM")
                            .param("scope_id", String.valueOf(teamAId))
                            .param("category_id", String.valueOf(catBId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.CATEGORY_NOT_FOUND.getCode()))
                    .andReturn().getResponse().getContentAsString();

            assertThat(body).doesNotContain(SECRET_BODY_B);
        }

        /** AC-B65: グローバル経路の正当な categoryId 指定は 200（非回帰）。 */
        @Test
        @DisplayName("AC-B65 グローバル経路の正当categoryId指定は200（非回帰）")
        void ac_b65_グローバル経路の正当categoryIdは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/bulletin/threads")
                            .param("scope_type", "TEAM")
                            .param("scope_id", String.valueOf(teamAId))
                            .param("category_id", String.valueOf(catAId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        /**
         * AC-B66: 保管庫フォルダ作成のボディ由来 {@code parentFolderId} も帰属検証される。
         *
         * <p><b>非空虚性</b>: adminA は権限ゲートを通過する。親フォルダのスコープ一致検証だけが、
         * 他テナントのフォルダを親に持つフォルダの生成（テナント間参照の混線）を防いでいる。</p>
         */
        @Test
        @DisplayName("AC-B66 保管庫フォルダ作成の越境parentFolderIdは409かつフォルダが作られない")
        void ac_b66_越境parentFolderIdの作成は409() throws Exception {
            long before = countFoldersInScope(ScopeType.TEAM, teamAId);

            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders", "teams", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createFolderBody("越境フォルダ", folderBId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH.getCode()));

            em.flush();
            em.clear();

            assertThat(countFoldersInScope(ScopeType.TEAM, teamAId))
                    .as("遮断されたのにフォルダが作成されてはならない")
                    .isEqualTo(before);
        }

        /** AC-B67: 自スコープの親フォルダを指定した作成は 201（非回帰）。 */
        @Test
        @DisplayName("AC-B67 自スコープのparentFolderId指定の作成は201（非回帰）")
        void ac_b67_正当parentFolderIdの作成は201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/{scopeType}/{scopeId}/bulletin/archive/folders", "teams", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    createFolderBody("子フォルダ", folderAId))))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createFolderBody(String name, UUID parentFolderId) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("parentFolderId", parentFolderId.toString());
            return payload;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> updateThreadBody(String title, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("priority", "INFO");
        return payload;
    }

    private Map<String, Object> createThreadBody(Long categoryId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("categoryId", categoryId);
        payload.put("title", "BULAUTHZ 新規スレッド");
        payload.put("body", "BULAUTHZ 新規本文");
        payload.put("priority", "INFO");
        payload.put("readTrackingMode", "COUNT_ONLY");
        return payload;
    }

    private Map<String, Object> replyBody(String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parentId", null);
        payload.put("body", body);
        return payload;
    }

    private Map<String, Object> updateReplyBody(String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        return payload;
    }

    private Map<String, Object> updateCategoryBody(String name) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("description", "裏目付テスト");
        payload.put("displayOrder", 0);
        payload.put("color", "#10B981");
        payload.put("postMinRole", "MEMBER");
        return payload;
    }

    private Map<String, Object> moveFolderBody(UUID folderId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("archiveFolderId", folderId.toString());
        return payload;
    }

    /** {@code membership.domain.ScopeType} を名前から解決する（bulletin 側の同名 enum と衝突するため）。 */
    private com.mannschaft.app.membership.domain.ScopeType membershipScope(String name) {
        return com.mannschaft.app.membership.domain.ScopeType.valueOf(name);
    }

    private long countThreadsInScope(ScopeType scopeType, Long scopeId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM bulletin_threads "
                                + "WHERE scope_type = :st AND scope_id = :sid AND deleted_at IS NULL")
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .getSingleResult()).longValue();
    }

    private long countFoldersInScope(ScopeType scopeType, Long scopeId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM bulletin_archive_folders "
                                + "WHERE scope_type = :st AND scope_id = :sid AND deleted_at IS NULL")
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .getSingleResult()).longValue();
    }

    private long countRepliesOfThread(Long threadId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM bulletin_replies "
                                + "WHERE thread_id = :tid AND deleted_at IS NULL")
                .setParameter("tid", threadId)
                .getSingleResult()).longValue();
    }

    private long countReadStatus(Long threadId, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM bulletin_read_status "
                                + "WHERE thread_id = :tid AND user_id = :uid")
                .setParameter("tid", threadId)
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    /**
     * roles を priority 込みで seed する。
     *
     * <p>test プロファイルは Flyway 無効（schema は Entity 由来）で {@code V2.014__seed_roles.sql} が
     * 走らないため roles は空である。{@code AccessControlService#hasRoleOrAbove} は
     * {@code roles.priority} の比較で成否が決まり、行が無いと<b>常に false</b> になる。
     * その状態では正当系（スレッド作成）まで 403 になり、テストが「遮断できている」と誤読される。</p>
     */
    private void seedRoles() {
        seedRole("SYSTEM_ADMIN", 1);
        seedRole("ADMIN", 2);
        seedRole("DEPUTY_ADMIN", 3);
        seedRole("MEMBER", 4);
        seedRole("SUPPORTER", 5);
        seedRole("GUEST", 6);
    }

    private void seedRole(String name, int priority) {
        Number existing = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existing.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long saveCategory(ScopeType scopeType, Long scopeId, String name, Long createdBy) {
        return categoryRepository.save(BulletinCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .description("裏目付テスト")
                .displayOrder(0)
                .color("#3B82F6")
                .postMinRole("MEMBER")
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long saveThread(ScopeType scopeType, Long scopeId, Long categoryId, Long authorId,
                            String title, String body, boolean archived) {
        BulletinThreadEntity entity = BulletinThreadEntity.builder()
                .categoryId(categoryId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .authorId(authorId)
                .title(title)
                .body(body)
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.INDIVIDUAL)
                .isArchived(archived)
                .build();
        return threadRepository.save(entity).getId();
    }

    /** アーカイブ済み（保管庫フォルダ所属）のスレッドを 1 件作る。 */
    private Long saveArchivedThread(ScopeType scopeType, Long scopeId, Long authorId,
                                    String title, UUID folderId) {
        BulletinThreadEntity entity = BulletinThreadEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .authorId(authorId)
                .title(title)
                .body(title + " 本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .isArchived(true)
                .archiveFolderId(folderId)
                .build();
        return threadRepository.save(entity).getId();
    }

    private Long saveReply(Long threadId, Long authorId, String body) {
        BulletinReplyEntity reply = replyRepository.save(BulletinReplyEntity.builder()
                .threadId(threadId)
                .depth(0)
                .authorId(authorId)
                .body(body)
                .build());
        // スレッド側の返信カウントを実運用と揃える（AC-B28 のカウント不変照合の前提）。
        BulletinThreadEntity thread = threadRepository.findById(threadId).orElseThrow();
        thread.incrementReplyCount();
        threadRepository.save(thread);
        return reply.getId();
    }

    private UUID saveFolder(ScopeType scopeType, Long scopeId, String name, Long createdBy) {
        return folderRepository.save(BulletinArchiveFolderEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .depth(0)
                .displayOrder(0)
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
                                + "VALUES (:email, 'BULAUTHZ', 'テスト', 'BULAUTHZ テスト', 'ACTIVE', "
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
                                + "CONCAT('bul-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('bulo-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
