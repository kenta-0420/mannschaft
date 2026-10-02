package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.dto.MatchApplicationResponse;
import com.mannschaft.app.village.dto.MatchApplicationReviewRequest;
import com.mannschaft.app.village.dto.MatchRecruitCreateRequest;
import com.mannschaft.app.village.dto.MatchRecruitListResponse;
import com.mannschaft.app.village.dto.MatchRecruitResponse;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitCategory;
import com.mannschaft.app.village.entity.enums.VillageMatchRecruitStatus;
import com.mannschaft.app.village.service.VillageMatchRecruitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 Phase 2 U9 — VillageMatchRecruitController MockMvc 契約テスト。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>characterization test（現契約の固定）</strong>であり、red → green の red テストではない。
 * BE は既に正しく、初回実行から green になるのが正常である。</p>
 *
 * <p>既存の {@link VillageMatchRecruitControllerIntegrationTest} は Controller Bean を
 * {@code @Autowired} して直接呼ぶ流儀であり、HTTP 層（URL パス・HTTP メソッド・JSON エンベロープ形状）を
 * 一切検証しない。本テストはその穴を MockMvc で塞ぐ。既存 IntegrationTest は挙動の回帰検知として残置。</p>
 *
 * <p>規約: 新規 Controller テストは MockMvc 経由必須（{@code TEST_CONVENTION.md} §Controller テスト）。</p>
 */
@WebMvcTest(VillageMatchRecruitController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageMatchRecruitController MockMvc 契約テスト（現契約の固定）")
class VillageMatchRecruitControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageMatchRecruitService matchRecruitService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID RECRUIT_ID = UUID.randomUUID();
    private static final UUID APPLICATION_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    private static final String BASE = "/api/v1/villages/{villageId}/match-recruits";

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    private MatchRecruitResponse recruit() {
        return new MatchRecruitResponse(
                RECRUIT_ID,
                VILLAGE_ID,
                USER_ID,
                "村人A",
                null,
                null,
                VillageMatchRecruitCategory.PRACTICE_MATCH,
                "練習試合の相手募集",
                "日曜午前でお願いします",
                LocalDate.of(2026, 8, 2),
                LocalTime.of(9, 0),
                LocalTime.of(12, 0),
                "市営グラウンド",
                1,
                "村内メッセージ",
                // TZ 境界での 9 時間ズレを避けるため、日時は文字列リテラルではなく LocalDateTime で組む
                LocalDateTime.of(2026, 7, 30, 23, 59),
                VillageMatchRecruitStatus.OPEN,
                LocalDateTime.of(2026, 7, 15, 10, 0));
    }

    private MatchApplicationResponse application(VillageMatchApplicationStatus status) {
        return new MatchApplicationResponse(
                APPLICATION_ID,
                RECRUIT_ID,
                200L,
                "村人B",
                null,
                null,
                "お願いします",
                status,
                USER_ID,
                LocalDateTime.of(2026, 7, 16, 9, 0),
                "よろしく",
                LocalDateTime.of(2026, 7, 15, 12, 0));
    }

    // ==================================================================
    // 一覧エンベロープ形状
    // ==================================================================

    @Test
    @DisplayName("GET 一覧 — エンベロープは {items, page, size, total}（Page 形状ではない）")
    void list_envelopeShape() throws Exception {
        given(matchRecruitService.listRecruits(eq(VILLAGE_ID), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .willReturn(MatchRecruitListResponse.of(List.of(recruit()), 0, 20, 1L));

        mockMvc.perform(get(BASE, VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").value(RECRUIT_ID.toString()))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                // Page 形状の別名は存在しない
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist())
                .andExpect(jsonPath("$.data.number").doesNotExist());
    }

    @Test
    @DisplayName("GET 一覧 — MatchRecruitResponse の項目名（venue / matchDate 等）を固定する")
    void list_itemShape() throws Exception {
        given(matchRecruitService.listRecruits(eq(VILLAGE_ID), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .willReturn(MatchRecruitListResponse.of(List.of(recruit()), 0, 20, 1L));

        mockMvc.perform(get(BASE, VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].category").value("PRACTICE_MATCH"))
                .andExpect(jsonPath("$.data.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data.items[0].matchDate").value("2026-08-02"))
                .andExpect(jsonPath("$.data.items[0].matchTimeStart").value("09:00:00"))
                .andExpect(jsonPath("$.data.items[0].matchTimeEnd").value("12:00:00"))
                // 募集の開催場所は venue（寄合の location とは別名である点に注意）
                .andExpect(jsonPath("$.data.items[0].venue").value("市営グラウンド"))
                .andExpect(jsonPath("$.data.items[0].location").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].requiredCount").value(1))
                .andExpect(jsonPath("$.data.items[0].postedByUserId").value(USER_ID));
    }

    // ==================================================================
    // F17.1 P1（DB Expand）— 作成リクエストの契約
    // ==================================================================

    /**
     * 設計書 §5.6 / AC-28・AC-24 に対応。
     *
     * <p>{@code match_date} のスポーツ固着（{@code NOT NULL}）を緩和し、日付を持たない募集
     * （マネージャー募集・引っ越し手伝い等）を登録できるようにする。DDL 面の緩和は
     * {@code FlywayExistingDataVillageRecruitCategoriesMigrationTest} が検証し、
     * 本テストは <b>Bean Validation（{@code @NotNull}）が緩和に追随していること</b>を固定する。</p>
     */
    @Nested
    @DisplayName("F17.1 P1 — 募集作成の契約（match_date 緩和）")
    class CreateContract {

        /** matchDate 以外は現行どおりの最小 JSON。 */
        private String createJson(String matchDateJsonValue) {
            return "{"
                    + "\"category\":\"OTHER\","
                    + "\"title\":\"引っ越し手伝い募集\","
                    + "\"matchDate\":" + matchDateJsonValue
                    + "}";
        }

        @Test
        @DisplayName("AC-28 matchDate を null で送っても 201 で作成できる（@NotNull が外れていること）")
        void create_withNullMatchDate_returns201() throws Exception {
            given(matchRecruitService.createRecruit(eq(VILLAGE_ID), any(), eq(USER_ID)))
                    .willReturn(recruit());

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createJson("null")))
                    .andExpect(status().isCreated());

            // Service まで matchDate=null が素通しで届くこと（Controller 層で握りつぶさない）
            ArgumentCaptor<MatchRecruitCreateRequest> captor =
                    ArgumentCaptor.forClass(MatchRecruitCreateRequest.class);
            verify(matchRecruitService).createRecruit(eq(VILLAGE_ID), captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().matchDate())
                    .as("日付を持たない募集は matchDate=null のまま Service へ渡る").isNull();
        }

        @Test
        @DisplayName("AC-28 matchDate フィールドを省略しても 201 で作成できる")
        void create_withoutMatchDateField_returns201() throws Exception {
            given(matchRecruitService.createRecruit(eq(VILLAGE_ID), any(), eq(USER_ID)))
                    .willReturn(recruit());

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\":\"OTHER\",\"title\":\"マネージャー募集\"}"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("AC-24 後方互換 — matchDate 有りの従来リクエストは従来どおり 201 で作成できる")
        void create_withMatchDate_stillWorks() throws Exception {
            given(matchRecruitService.createRecruit(eq(VILLAGE_ID), any(), eq(USER_ID)))
                    .willReturn(recruit());

            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createJson("\"2026-08-02\"")))
                    .andExpect(status().isCreated());

            ArgumentCaptor<MatchRecruitCreateRequest> captor =
                    ArgumentCaptor.forClass(MatchRecruitCreateRequest.class);
            verify(matchRecruitService).createRecruit(eq(VILLAGE_ID), captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().matchDate())
                    .as("従来どおり matchDate が束縛されること（緩和は既存契約を壊さない）")
                    .isEqualTo(LocalDate.of(2026, 8, 2));
        }

        @Test
        @DisplayName("緩和の範囲は matchDate のみ — title 空欄は従来どおり 400 で弾かれる")
        void create_blankTitle_stillRejected() throws Exception {
            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"category\":\"OTHER\",\"title\":\"\",\"matchDate\":null}"))
                    .andExpect(status().isBadRequest());

            verify(matchRecruitService, never()).createRecruit(any(), any(), any());
        }

        @Test
        @DisplayName("緩和の範囲は matchDate のみ — category 未指定は従来どおり 400 で弾かれる")
        void create_missingCategory_stillRejected() throws Exception {
            mockMvc.perform(post(BASE, VILLAGE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"カテゴリ無し\",\"matchDate\":null}"))
                    .andExpect(status().isBadRequest());

            verify(matchRecruitService, never()).createRecruit(any(), any(), any());
        }
    }

    // ==================================================================
    // 一覧クエリの enum 契約
    // ==================================================================

    @Nested
    @DisplayName("一覧クエリの enum 契約")
    class ListQueryEnumContract {

        @Test
        @DisplayName("GET ?category=PRACTICE_MATCH&status=OPEN は 200")
        void list_validEnums_returns200() throws Exception {
            given(matchRecruitService.listRecruits(eq(VILLAGE_ID),
                    eq(VillageMatchRecruitCategory.PRACTICE_MATCH),
                    eq(VillageMatchRecruitStatus.OPEN), any(), any(), anyInt(), anyInt(), any()))
                    .willReturn(MatchRecruitListResponse.of(List.of(recruit()), 0, 20, 1L));

            mockMvc.perform(get(BASE, VILLAGE_ID)
                            .param("category", "PRACTICE_MATCH")
                            .param("status", "OPEN"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET ?status=BOGUS は 400 VILLAGE_029（Controller が明示 parse して VILLAGE_FIELD_INVALID）")
        void list_invalidStatus_returnsFieldInvalid() throws Exception {
            mockMvc.perform(get(BASE, VILLAGE_ID).param("status", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_029"));

            verify(matchRecruitService, never())
                    .listRecruits(any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("GET ?category=BOGUS は 400 VILLAGE_029")
        void list_invalidCategory_returnsFieldInvalid() throws Exception {
            mockMvc.perform(get(BASE, VILLAGE_ID).param("category", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_029"));
        }

        @Test
        @DisplayName("GET — category / status は小文字でも受理される（Controller が toUpperCase する）")
        void list_lowercaseEnums_areAccepted() throws Exception {
            given(matchRecruitService.listRecruits(eq(VILLAGE_ID),
                    eq(VillageMatchRecruitCategory.REFEREE),
                    eq(VillageMatchRecruitStatus.CLOSED), any(), any(), anyInt(), anyInt(), any()))
                    .willReturn(MatchRecruitListResponse.of(List.of(), 0, 20, 0L));

            mockMvc.perform(get(BASE, VILLAGE_ID)
                            .param("category", "referee")
                            .param("status", "closed"))
                    .andExpect(status().isOk());

            verify(matchRecruitService).listRecruits(eq(VILLAGE_ID),
                    eq(VillageMatchRecruitCategory.REFEREE),
                    eq(VillageMatchRecruitStatus.CLOSED), any(), any(), anyInt(), anyInt(), any());
        }
    }

    // ==================================================================
    // 応募審査の契約
    // ==================================================================

    @Nested
    @DisplayName("応募審査の契約")
    class ApplicationReviewContract {

        @Test
        @DisplayName("POST /{rid}/applications/{aid}/review — body は {status, reviewComment}（action ではない）で 200")
        void review_bodyUsesStatusField() throws Exception {
            given(matchRecruitService.reviewApplication(eq(VILLAGE_ID), eq(RECRUIT_ID), eq(APPLICATION_ID),
                    any(MatchApplicationReviewRequest.class), eq(USER_ID)))
                    .willReturn(application(VillageMatchApplicationStatus.ACCEPTED));

            String body = """
                    {
                      "status": "ACCEPTED",
                      "reviewComment": "よろしく"
                    }
                    """;

            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/review",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.data.reviewComment").value("よろしく"));

            ArgumentCaptor<MatchApplicationReviewRequest> captor =
                    ArgumentCaptor.forClass(MatchApplicationReviewRequest.class);
            verify(matchRecruitService).reviewApplication(eq(VILLAGE_ID), eq(RECRUIT_ID), eq(APPLICATION_ID),
                    captor.capture(), eq(USER_ID));
            assertThat(captor.getValue().status()).isEqualTo(VillageMatchApplicationStatus.ACCEPTED);
            assertThat(captor.getValue().reviewComment()).isEqualTo("よろしく");
        }

        @Test
        @DisplayName("POST .../review — status: REJECTED も受理される")
        void review_rejected() throws Exception {
            given(matchRecruitService.reviewApplication(eq(VILLAGE_ID), eq(RECRUIT_ID), eq(APPLICATION_ID),
                    any(MatchApplicationReviewRequest.class), eq(USER_ID)))
                    .willReturn(application(VillageMatchApplicationStatus.REJECTED));

            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/review",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "status": "REJECTED", "reviewComment": "今回は見送ります" }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
        }

        @Test
        @DisplayName("POST .../review — body に action を送っても status が欠けるので 400。FE の誤形状を固定")
        void review_actionField_returns400() throws Exception {
            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/review",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "action": "ACCEPT" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(matchRecruitService, never())
                    .reviewApplication(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("POST .../review — status に不正な enum 値は 400")
        void review_invalidStatusEnum_returns400() throws Exception {
            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/review",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    { "status": "APPROVE" }
                                    """))
                    .andExpect(status().isBadRequest());

            verify(matchRecruitService, never())
                    .reviewApplication(any(), any(), any(), any(), any());
        }
    }

    // ==================================================================
    // 旧エンドポイントが存在しないことの固定
    // ==================================================================

    @Nested
    @DisplayName("旧 accept / reject エンドポイントは存在しない")
    class LegacyEndpointsRemoved {

        @Test
        @DisplayName("POST /{rid}/applications/{aid}/accept は 404")
        void accept_returns404() throws Exception {
            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/accept",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());

            verify(matchRecruitService, never())
                    .reviewApplication(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("POST /{rid}/applications/{aid}/reject は 404")
        void reject_returns404() throws Exception {
            mockMvc.perform(post(BASE + "/{recruitId}/applications/{applicationId}/reject",
                            VILLAGE_ID, RECRUIT_ID, APPLICATION_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());

            verify(matchRecruitService, never())
                    .reviewApplication(any(), any(), any(), any(), any());
        }
    }

    // ==================================================================
    // 応募一覧の形状
    // ==================================================================

    @Test
    @DisplayName("GET /{rid}/applications — $.data は素の配列（items ラップではない）")
    void listApplications_envelopeIsBareArray() throws Exception {
        given(matchRecruitService.listApplications(VILLAGE_ID, RECRUIT_ID, USER_ID))
                .willReturn(List.of(application(VillageMatchApplicationStatus.PENDING)));

        mockMvc.perform(get(BASE + "/{recruitId}/applications", VILLAGE_ID, RECRUIT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(APPLICATION_ID.toString()))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].applicantUserId").value(200))
                .andExpect(jsonPath("$.data.items").doesNotExist())
                .andExpect(jsonPath("$.data.content").doesNotExist());
    }
}
