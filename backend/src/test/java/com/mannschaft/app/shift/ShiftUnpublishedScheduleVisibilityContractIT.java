package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260826-2127 — 未公開シフト情報の遮断（試練 / red 先行）。
 *
 * <p>正本設計: {@code docs/features/F03.5_shift/05_unpublished_visibility.md}（v1.1）。
 * 受け入れ条件 AC-1〜AC-17 は同設計書 §7 が正本であり、本クラスはそのうち
 * <b>API 経由で観測できるもの</b>を固定する。</p>
 *
 * <p><b>隔ての軸</b>（設計書 §2.1）はステータス単独ではなく「情報の層 × ステータス」である。
 * 一度目の軍議の「非管理者に DRAFT/COLLECTING/ADJUSTING を返さない」案は
 * {@code my/shift-request.vue} の希望提出画面を空にする機能回帰であったため破棄された。</p>
 *
 * <table>
 *   <caption>非管理者に対する開閉表</caption>
 *   <tr><th>ステータス</th><th>メタ</th><th>枠の骨格</th><th>割当</th></tr>
 *   <tr><td>DRAFT</td><td>✕(404)</td><td>✕</td><td>✕</td></tr>
 *   <tr><td>COLLECTING / ADJUSTING</td><td>○</td><td>○</td><td>✕(マスク)</td></tr>
 *   <tr><td>PUBLISHED</td><td>○</td><td>○</td><td>○</td></tr>
 *   <tr><td>ARCHIVED + publishedAt あり</td><td>○</td><td>○</td><td>○</td></tr>
 *   <tr><td>ARCHIVED + publishedAt なし</td><td>✕(404)</td><td>✕</td><td>✕</td></tr>
 * </table>
 *
 * <p><b>本クラスで固定しない AC</b>（別経路のため）:</p>
 * <ul>
 *   <li><b>AC-13</b> — 既存テストのフィクスチャ差し替えによる緑化。試練の段階では実装が無いため
 *       既存テストには手を触れない（出陣の役目）。</li>
 *   <li><b>AC-14</b> — {@code ShiftPreferenceReminderBatchService} のリマインド通知。
 *       バッチ経路に閲覧者は存在せず、既存 UT が番人となる。</li>
 *   <li><b>AC-15</b> — {@code ShiftScheduleList.vue} の FE フィルタ削除。FE の担当。</li>
 * </ul>
 *
 * <p>金型は同ドメインの {@code ShiftScheduleScopeContractIT}
 *（{@code addFilters=false} + 実 MySQL + 手動 SecurityContext + {@code MembershipTestHelper}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-2127 未公開シフト情報の遮断契約テスト（試練）")
class ShiftUnpublishedScheduleVisibilityContractIT extends AbstractMySqlIntegrationTest {

    private static final String SCHEDULES_PATH = "/api/v1/shifts/schedules";
    private static final String SEARCH_PATH = "/api/v1/search";

    /** 検索用の共通キーワード（全フィクスチャのタイトルに含める） */
    private static final String TITLE_KEYWORD = "Cmp2127Kw";

    /**
     * 検証対象のフィクスチャ。ステータスと {@code publishedAt} の組み合わせが可視性を決める。
     *
     * <p>設計書 §3.6 の 3 分類:
     * {@code HIDDEN}（存在ごと秘匿）／{@code MASKED}（メタ・骨格は開き割当を伏せる）／
     * {@code FULL}（全量）。</p>
     */
    private enum Fixture {
        /** 下書き。非管理者には存在ごと秘匿 */
        DRAFT(ShiftScheduleStatus.DRAFT, false, Visibility.HIDDEN),
        /** 希望収集中。メタ・骨格は開き、割当のみ伏せる */
        COLLECTING(ShiftScheduleStatus.COLLECTING, false, Visibility.MASKED),
        /** 調整中。同上 */
        ADJUSTING(ShiftScheduleStatus.ADJUSTING, false, Visibility.MASKED),
        /** 公開済み（publishedAt あり） */
        PUBLISHED(ShiftScheduleStatus.PUBLISHED, true, Visibility.FULL),
        /**
         * 公開済みだが publishedAt が NULL。AC-17 の番人。
         * DB に整合制約が無く（V3.070）、{@code ShiftMapperTest} 等が実際に作る形。
         */
        PUBLISHED_WITHOUT_TIMESTAMP(ShiftScheduleStatus.PUBLISHED, false, Visibility.FULL),
        /** 公開を経てアーカイブされたもの */
        ARCHIVED_PUBLISHED(ShiftScheduleStatus.ARCHIVED, true, Visibility.FULL),
        /**
         * DRAFT から直接アーカイブされたもの（publishedAt が NULL）。AC-7 の番人。
         * {@code transitionStatus} に遷移元ガードが無いため実運用で作れる。
         */
        ARCHIVED_WITHOUT_TIMESTAMP(ShiftScheduleStatus.ARCHIVED, false, Visibility.HIDDEN);

        private final ShiftScheduleStatus status;
        private final boolean published;
        private final Visibility visibility;

        Fixture(ShiftScheduleStatus status, boolean published, Visibility visibility) {
            this.status = status;
            this.published = published;
            this.visibility = visibility;
        }
    }

    /** 非管理者から見た可視性の 3 分類（設計書 §3.6） */
    private enum Visibility {
        /** 存在ごと秘匿（一覧から除外・単体404・枠404・PDF404・検索ヒットせず） */
        HIDDEN,
        /** メタと骨格は開き、割当のみ伏せる */
        MASKED,
        /** 全量 */
        FULL
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @Autowired
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long otherTeamId;

    private Long systemAdminId;   // SYSTEM_ADMIN。当該チームの非メンバー（AC-11 の要点）
    private Long adminId;         // 当該チーム ADMIN
    private Long deputyAdminId;   // 当該チーム DEPUTY_ADMIN
    private Long memberId;        // 当該チームの一般メンバー（非管理者）
    private Long supporterId;     // 当該チーム SUPPORTER（常に403）
    private Long otherTeamAdminId;// 別チーム ADMIN（常に403）
    private Long outsiderId;      // 無所属（常に403）

    private final Map<Fixture, Long> scheduleIds = new EnumMap<>(Fixture.class);
    private final Map<Fixture, Long> slotIds = new EnumMap<>(Fixture.class);

    @BeforeEach
    void setUp() throws Exception {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        teamId = insertTeam("CMP2127 対象チーム");
        otherTeamId = insertTeam("CMP2127 別チーム");

        systemAdminId = insertUser("cmp2127-sysadmin@example.com");
        adminId = insertUser("cmp2127-admin@example.com");
        deputyAdminId = insertUser("cmp2127-deputy@example.com");
        memberId = insertUser("cmp2127-member@example.com");
        supporterId = insertUser("cmp2127-supporter@example.com");
        otherTeamAdminId = insertUser("cmp2127-other-admin@example.com");
        outsiderId = insertUser("cmp2127-outsider@example.com");

        // SYSTEM_ADMIN はプラットフォーム級。当該チームの memberships は敢えて張らない
        // （AC-11「メンバーでなくても全量が見えること」を検証するため）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, deputyAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyAdminId, "DEPUTY_ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, otherTeamAdminId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, otherTeamAdminId, "ADMIN", otherTeamId, null);

        int day = 1;
        for (Fixture fixture : Fixture.values()) {
            ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title(title(fixture))
                    .note(noteToken(fixture))
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, day))
                    .endDate(LocalDate.of(2026, 3, day + 1))
                    .status(fixture.status)
                    .publishedAt(fixture.published ? LocalDateTime.of(2026, 2, 20, 10, 0) : null)
                    .createdBy(adminId)
                    .build());
            scheduleIds.put(fixture, schedule.getId());

            // 割当済みの枠を 1 件ずつ用意する。マスクの有無はこの assignedUserIds で観測する。
            ShiftSlotEntity slot = slotRepository.save(ShiftSlotEntity.builder()
                    .scheduleId(schedule.getId())
                    .slotDate(LocalDate.of(2026, 3, day))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .requiredCount(2)
                    .assignedUserIds(objectMapper.writeValueAsString(List.of(memberId)))
                    .note("CMP2127 枠メモ")
                    .build());
            slotIds.put(fixture, slot.getId());
            day += 2;
        }

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1: 一覧・期間指定一覧から未公開シフト表が除外される（中身で検証）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-1: 非管理者の一覧・期間指定一覧から未公開シフト表が除外される")
    class Ac1ListExcludesHidden {

        @ParameterizedTest(name = "{0} は一覧に含まれない")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("未公開シフト表は非管理者の一覧に含まれない")
        void 未公開は一覧に含まれない(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", not(hasItem(title(fixture)))));
        }

        @ParameterizedTest(name = "{0} は期間指定一覧に含まれない")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("未公開シフト表は非管理者の期間指定一覧にも含まれない（L1 の迂回路）")
        void 未公開は期間指定一覧に含まれない(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH)
                            .param("teamId", teamId.toString())
                            .param("from", "2026-03-01")
                            .param("to", "2026-03-31"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", not(hasItem(title(fixture)))));
        }

        @ParameterizedTest(name = "{0} は一覧に含まれる")
        @EnumSource(value = Fixture.class,
                names = {"COLLECTING", "ADJUSTING", "PUBLISHED", "PUBLISHED_WITHOUT_TIMESTAMP", "ARCHIVED_PUBLISHED"})
        @DisplayName("★正常系★ 秘匿対象でないシフト表は非管理者の一覧に残る（絞りすぎの回帰防止）")
        void 秘匿対象外は一覧に残る(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", hasItem(title(fixture))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2 / AC-3: 未公開シフト表の単体取得・枠一覧は 404
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-2 / AC-3: 未公開シフト表の単体取得・枠一覧は404")
    class Ac2Ac3NotFound {

        @ParameterizedTest(name = "{0} の単体取得は404")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("AC-2: 未公開シフト表の単体取得は404（SHIFT_001）")
        void 未公開の単体取得は404(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}", scheduleIds.get(fixture)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SHIFT_001"));
        }

        @ParameterizedTest(name = "{0} の枠一覧は404")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("AC-3: 未公開シフト表の枠一覧は404")
        void 未公開の枠一覧は404(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleIds.get(fixture)))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4: COLLECTING / ADJUSTING の枠一覧は 200 で割当だけ伏せる
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-4: COLLECTING / ADJUSTING の枠一覧は200で割当のみマスクされる")
    class Ac4AssignmentMasked {

        @ParameterizedTest(name = "{0} の枠一覧は200・assignedUserIds は空配列")
        @EnumSource(value = Fixture.class, names = {"COLLECTING", "ADJUSTING"})
        @DisplayName("割当は空配列に伏せられる（null は FE が .length で落ちるため禁止）")
        void 割当は空配列に伏せられる(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleIds.get(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].assignedUserIds").isArray())
                    .andExpect(jsonPath("$.data[0].assignedUserIds", empty()));
        }

        @ParameterizedTest(name = "{0} は assignmentMasked=true")
        @EnumSource(value = Fixture.class, names = {"COLLECTING", "ADJUSTING"})
        @DisplayName("伏せたことは assignmentMasked=true で示される（U-1 案B・マスター裁可済み）")
        void 伏せたことはフラグで示される(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleIds.get(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].assignmentMasked").value(true));
        }

        @ParameterizedTest(name = "{0} でも枠の骨格は返る")
        @EnumSource(value = Fixture.class, names = {"COLLECTING", "ADJUSTING"})
        @DisplayName("枠の骨格（日時・必要人数）は従来どおり返る（希望提出の判断材料）")
        void 枠の骨格は返る(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleIds.get(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].time.slotDate", notNullValue()))
                    .andExpect(jsonPath("$.data[0].time.startTime", notNullValue()))
                    .andExpect(jsonPath("$.data[0].time.endTime", notNullValue()))
                    .andExpect(jsonPath("$.data[0].position.requiredCount").value(2));
        }

        @ParameterizedTest(name = "{0} は割当が見える")
        @EnumSource(value = Fixture.class,
                names = {"PUBLISHED", "PUBLISHED_WITHOUT_TIMESTAMP", "ARCHIVED_PUBLISHED"})
        @DisplayName("★正常系★ 公開済みシフト表の割当はマスクされない（R9 の回帰防止）")
        void 公開済みの割当はマスクされない(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleIds.get(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].assignedUserIds", hasItem(memberId.intValue())))
                    .andExpect(jsonPath("$.data[0].assignmentMasked").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5: 未公開・COLLECTING・ADJUSTING の PDF は 404
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-5: 未公開・調整中のPDFは404")
    class Ac5PdfNotFound {

        @ParameterizedTest(name = "{0} のチームPDFは404")
        @EnumSource(value = Fixture.class,
                names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP", "COLLECTING", "ADJUSTING"})
        @DisplayName("チームPDFは404（§6【v2.2】の既存宣言との整合）")
        void 未公開のチームPDFは404(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/pdf", scheduleIds.get(fixture))
                            .param("layout", "team"))
                    .andExpect(status().isNotFound());
        }

        @ParameterizedTest(name = "{0} の個人PDFは404")
        @EnumSource(value = Fixture.class,
                names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP", "COLLECTING", "ADJUSTING"})
        @DisplayName("個人PDFも404（割当欄が空の無意味な紙を出さない）")
        void 未公開の個人PDFは404(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/pdf", scheduleIds.get(fixture))
                            .param("layout", "personal"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-6: グローバル検索から未公開シフト表が消える（note 経由も含む）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-6: 非管理者のグローバル検索に未公開シフト表が出ない")
    class Ac6SearchExcludesHidden {

        @ParameterizedTest(name = "{0} は検索結果に出ない")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("タイトル検索で未公開シフト表がヒットしない")
        void 未公開はタイトル検索でヒットしない(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SEARCH_PATH).param("q", TITLE_KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.shifts[*].title", not(hasItem(title(fixture)))));
        }

        @ParameterizedTest(name = "{0} は note 検索でもヒットしない")
        @EnumSource(value = Fixture.class, names = {"DRAFT", "ARCHIVED_WITHOUT_TIMESTAMP"})
        @DisplayName("note にしかない語で検索してもヒットしない（総当りによる note 推定の封鎖）")
        void 未公開はnote検索でもヒットしない(Fixture fixture) throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SEARCH_PATH).param("q", noteToken(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.shifts", empty()));
        }

        @Test
        @DisplayName("★正常系★ 管理者の検索では未公開シフト表がヒットする")
        void 管理者の検索では未公開もヒットする() throws Exception {
            setAuth(adminId);
            mockMvc.perform(get(SEARCH_PATH).param("q", noteToken(Fixture.DRAFT)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.shifts[*].title",
                            hasItem(title(Fixture.DRAFT))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7 / AC-17: publishedAt の非対称な扱い
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-7 / AC-17: publishedAt の非対称な扱い")
    class Ac7Ac17PublishedAtAsymmetry {

        @Test
        @DisplayName("AC-7: ARCHIVED かつ publishedAt が NULL は DRAFT と同一に扱われる（fail-closed）")
        void アーカイブ未公開はDRAFTと同一に扱われる() throws Exception {
            Fixture fixture = Fixture.ARCHIVED_WITHOUT_TIMESTAMP;
            Long id = scheduleIds.get(fixture);

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", not(hasItem(title(fixture)))));

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}", id)).andExpect(status().isNotFound());

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", id)).andExpect(status().isNotFound());

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/pdf", id).param("layout", "team"))
                    .andExpect(status().isNotFound());

            setAuth(memberId);
            mockMvc.perform(get(SEARCH_PATH).param("q", noteToken(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.shifts", empty()));
        }

        @Test
        @DisplayName("AC-17: PUBLISHED かつ publishedAt が NULL は公開済みとして扱われる")
        void 公開済みでpublishedAtがNULLでも公開扱い() throws Exception {
            Fixture fixture = Fixture.PUBLISHED_WITHOUT_TIMESTAMP;
            Long id = scheduleIds.get(fixture);

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", hasItem(title(fixture))));

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}", id)).andExpect(status().isOk());

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].assignedUserIds", hasItem(memberId.intValue())));

            setAuth(memberId);
            mockMvc.perform(get(SEARCH_PATH).param("q", noteToken(fixture)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.shifts[*].title", hasItem(title(fixture))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8 / AC-9: 希望提出フロー・調整中の可視を壊さない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-8 / AC-9: 非回帰（希望提出フローと調整中の可視）")
    class Ac8Ac9NoFunctionalRegression {

        @Test
        @DisplayName("AC-8: メンバーは COLLECTING を一覧で取得し、枠を見て、希望を提出できる")
        void メンバーはCOLLECTINGに希望提出できる() throws Exception {
            Long scheduleId = scheduleIds.get(Fixture.COLLECTING);

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", hasItem(title(Fixture.COLLECTING))));

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(slotIds.get(Fixture.COLLECTING)));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scheduleId", scheduleId);
            body.put("slotId", slotIds.get(Fixture.COLLECTING));
            body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
            body.put("preference", "AVAILABLE");

            setAuth(memberId);
            mockMvc.perform(post("/api/v1/shifts/requests")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("AC-9: メンバーは ADJUSTING を一覧・単体で取得でき status も受け取れる")
        void メンバーはADJUSTINGを取得できる() throws Exception {
            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].content.title", hasItem(title(Fixture.ADJUSTING))));

            setAuth(memberId);
            mockMvc.perform(get(SCHEDULES_PATH + "/{id}", scheduleIds.get(Fixture.ADJUSTING)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status.status").value("ADJUSTING"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10 / AC-11: 管理者・SYSTEM_ADMIN は全量
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-10 / AC-11: 管理者・SYSTEM_ADMIN は全ステータスで全量")
    class Ac10Ac11AdminFullAccess {

        @ParameterizedTest(name = "ADMIN は {0} を全量取得できる")
        @EnumSource(Fixture.class)
        @DisplayName("AC-10: ADMIN は全ステータスを全量取得でき、割当も伏せられない")
        void ADMINは全量取得できる(Fixture fixture) throws Exception {
            assertFullAccess(adminId, fixture);
        }

        @ParameterizedTest(name = "DEPUTY_ADMIN は {0} を全量取得できる")
        @EnumSource(Fixture.class)
        @DisplayName("AC-10: DEPUTY_ADMIN も同様に全量取得できる")
        void DEPUTY_ADMINは全量取得できる(Fixture fixture) throws Exception {
            assertFullAccess(deputyAdminId, fixture);
        }

        @ParameterizedTest(name = "非メンバーの SYSTEM_ADMIN は {0} を全量取得できる")
        @EnumSource(Fixture.class)
        @DisplayName("AC-11(a): 当該チームの非メンバーでも SYSTEM_ADMIN は全量取得できる")
        void SYSTEM_ADMINは非メンバーでも全量取得できる(Fixture fixture) throws Exception {
            assertFullAccess(systemAdminId, fixture);
        }

        @ParameterizedTest(name = "非メンバーの SYSTEM_ADMIN は {0} のPDFを取得できる")
        @EnumSource(Fixture.class)
        @DisplayName("AC-11(b): PDF も 403/404 で弾かれない（checkMemberAndNotSupporter の SYSTEM_ADMIN 短絡）")
        void SYSTEM_ADMINは非メンバーでもPDFを取得できる(Fixture fixture) throws Exception {
            setAuth(systemAdminId);
            int pdfStatus = mockMvc.perform(get(SCHEDULES_PATH + "/{id}/pdf", scheduleIds.get(fixture))
                            .param("layout", "team"))
                    .andReturn().getResponse().getStatus();
            // PDF のレンダリング失敗（500）は本テストの関心外。認可・可視性で弾かれないことだけを固定する。
            assertThat(pdfStatus)
                    .as("SYSTEM_ADMIN のPDFが認可・可視性で弾かれないこと（%s）", fixture)
                    .isNotIn(403, 404);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: 既存の認可 403 契約は 1 件も変わらない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-12: 認可403契約はステータスに依らず不変（二層順序の番人）")
    class Ac12AuthorizationUnchanged {

        @ParameterizedTest(name = "SUPPORTER は {0} で403")
        @EnumSource(Fixture.class)
        @DisplayName("SUPPORTER は全ステータスで403（404 に化けない）")
        void サポーターは常に403(Fixture fixture) throws Exception {
            assertAlwaysForbidden(supporterId, fixture);
        }

        @ParameterizedTest(name = "別チームADMIN は {0} で403")
        @EnumSource(Fixture.class)
        @DisplayName("別 scope の ADMIN は全ステータスで403（403/404 の差で存在を観測させない）")
        void 別チームADMINは常に403(Fixture fixture) throws Exception {
            assertAlwaysForbidden(otherTeamAdminId, fixture);
        }

        @ParameterizedTest(name = "無所属は {0} で403")
        @EnumSource(Fixture.class)
        @DisplayName("無所属ユーザーは全ステータスで403")
        void 無所属は常に403(Fixture fixture) throws Exception {
            assertAlwaysForbidden(outsiderId, fixture);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 管理者相当が全ステータスで一覧・単体・枠（割当込み）を取得できることを固定する。 */
    private void assertFullAccess(Long userId, Fixture fixture) throws Exception {
        Long scheduleId = scheduleIds.get(fixture);

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].content.title", hasItem(title(fixture))));

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH + "/{id}", scheduleId))
                .andExpect(status().isOk());

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].assignedUserIds", hasItem(memberId.intValue())))
                .andExpect(jsonPath("$.data[0].assignmentMasked").value(false));
    }

    /** 認可で弾かれる側が、可視性 404 に先んじて常に 403 を受け取ることを固定する。 */
    private void assertAlwaysForbidden(Long userId, Fixture fixture) throws Exception {
        Long scheduleId = scheduleIds.get(fixture);

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH).param("teamId", teamId.toString()))
                .andExpect(status().isForbidden());

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH + "/{id}", scheduleId))
                .andExpect(status().isForbidden());

        setAuth(userId);
        mockMvc.perform(get(SCHEDULES_PATH + "/{id}/slots", scheduleId))
                .andExpect(status().isForbidden());
    }

    private static String title(Fixture fixture) {
        return TITLE_KEYWORD + "-" + fixture.name();
    }

    /** そのシフト表の note にしか出現しない語（AC-6 の note 経由推定の検証に使う） */
    private static String noteToken(Fixture fixture) {
        return "Cmp2127Note" + fixture.name().replace("_", "");
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'CMP2127', 'テスト', 'CMP2127 テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
