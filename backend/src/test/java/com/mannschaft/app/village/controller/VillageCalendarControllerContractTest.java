package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.dto.CalendarEventListResponse;
import com.mannschaft.app.village.dto.CalendarEventResponse;
import com.mannschaft.app.village.service.VillageCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 Phase 2 U8 — VillageCalendarController MockMvc 契約テスト。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>characterization test（現契約の固定）</strong>であり、red → green の red テストではない。
 * BE は既に正しく、初回実行から green になるのが正常である。</p>
 *
 * <p>既存の {@link VillageCalendarControllerIntegrationTest} は Controller Bean を {@code @Autowired}
 * して直接呼ぶ流儀であり、HTTP 層（URL パス・HTTP メソッド・クエリパラメータのバインド・JSON エンベロープ
 * 形状）を一切検証しない。本テストはその穴を MockMvc で塞ぐ。既存 IntegrationTest は挙動の回帰検知として
 * 残置しており、本テストはそれを置き換えるものではなく補完する。</p>
 *
 * <p>規約: 新規 Controller テストは MockMvc 経由必須（{@code TEST_CONVENTION.md} §Controller テスト）。</p>
 */
@WebMvcTest(VillageCalendarController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 VillageCalendarController MockMvc 契約テスト（現契約の固定）")
class VillageCalendarControllerContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageCalendarService calendarService;

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
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    private static final String BASE = "/api/v1/villages/{villageId}/calendar-events";

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    /**
     * 毎年繰返イベント。{@code eventDate} は「登録年」の 3/3（桃の節句）であり、
     * 検索対象年とは一致しない点が本契約の要。
     */
    private CalendarEventResponse annualRecurringEvent() {
        return new CalendarEventResponse(
                EVENT_ID,
                VILLAGE_ID,
                "桃の節句",
                "ひな祭り",
                LocalDate.of(2020, 3, 3),   // 登録年は 2020
                null,
                true,                        // 毎年繰返
                "🎎",
                "#FFC0CB",
                USER_ID,
                null,
                // TZ 境界での 9 時間ズレを避けるため、日時は文字列リテラルではなく LocalDateTime で組む
                LocalDateTime.of(2020, 3, 1, 10, 0));
    }

    // ==================================================================
    // エンベロープ形状
    // ==================================================================

    @Test
    @DisplayName("GET ?year=2025&month=3 — エンベロープは {items, year, month}（items 配列 + 問い合わせた年月のエコー）")
    void listByMonth_envelopeShape() throws Exception {
        given(calendarService.listEventsByMonth(VILLAGE_ID, 2025, 3, USER_ID))
                .willReturn(new CalendarEventListResponse(List.of(annualRecurringEvent()), 2025, 3));

        mockMvc.perform(get(BASE, VILLAGE_ID)
                        .param("year", "2025")
                        .param("month", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(2025))
                .andExpect(jsonPath("$.data.month").value(3))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].id").value(EVENT_ID.toString()))
                // Page 形状ではない
                .andExpect(jsonPath("$.data.content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                // 範囲クエリのエコーではない
                .andExpect(jsonPath("$.data.from").doesNotExist())
                .andExpect(jsonPath("$.data.to").doesNotExist());
    }

    // ==================================================================
    // year / month の意味論（範囲クエリではない）
    // ==================================================================

    @Test
    @DisplayName("GET — from / to は無視される（範囲クエリではなく year/month 指定の月別 API）")
    void listByMonth_ignoresFromAndTo() throws Exception {
        given(calendarService.listEventsByMonth(VILLAGE_ID, 2025, 3, USER_ID))
                .willReturn(new CalendarEventListResponse(List.of(annualRecurringEvent()), 2025, 3));

        mockMvc.perform(get(BASE, VILLAGE_ID)
                        .param("year", "2025")
                        .param("month", "3")
                        // FE が送っていた範囲パラメータ。BE は受け取らず、結果に影響しない
                        .param("from", "2025-03-01")
                        .param("to", "2025-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(2025))
                .andExpect(jsonPath("$.data.month").value(3));

        // from / to が渡っても Service に届くのは year / month のみ
        verify(calendarService).listEventsByMonth(VILLAGE_ID, 2025, 3, USER_ID);
    }

    @Test
    @DisplayName("GET — from / to だけを送っても year/month は現在日時にフォールバックする（範囲は効かない）")
    void listByMonth_fromToOnly_fallsBackToCurrentMonth() throws Exception {
        LocalDate today = LocalDate.now();
        given(calendarService.listEventsByMonth(VILLAGE_ID, today.getYear(), today.getMonthValue(), USER_ID))
                .willReturn(new CalendarEventListResponse(List.of(), today.getYear(), today.getMonthValue()));

        mockMvc.perform(get(BASE, VILLAGE_ID)
                        .param("from", "2025-03-01")
                        .param("to", "2025-03-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(today.getYear()))
                .andExpect(jsonPath("$.data.month").value(today.getMonthValue()));

        verify(calendarService).listEventsByMonth(VILLAGE_ID, today.getYear(), today.getMonthValue(), USER_ID);
        // 2025/3 は問い合わせられない（from/to は範囲として解釈されない）
        verify(calendarService, never()).listEventsByMonth(VILLAGE_ID, 2025, 3, USER_ID);
    }

    @Test
    @DisplayName("GET — year/month 未指定なら現在の年月にフォールバックする")
    void listByMonth_defaultsToCurrentYearMonth() throws Exception {
        LocalDate today = LocalDate.now();
        given(calendarService.listEventsByMonth(VILLAGE_ID, today.getYear(), today.getMonthValue(), USER_ID))
                .willReturn(new CalendarEventListResponse(List.of(), today.getYear(), today.getMonthValue()));

        mockMvc.perform(get(BASE, VILLAGE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.year").value(today.getYear()))
                .andExpect(jsonPath("$.data.month").value(today.getMonthValue()));
    }

    @Test
    @DisplayName("GET — 毎年繰返イベントは登録年と異なる年の同月クエリでも返る（eventDate の年は登録年のまま）")
    void listByMonth_annualRecurringAppearsInDifferentYear() throws Exception {
        // 2020 年登録の桃の節句が、2025/3 の問い合わせで返る。
        // VillageCalendarService は MONTH(event_date)=:month で年を無視するため、
        // この意味論は範囲クエリ（from/to）では表現できない。
        given(calendarService.listEventsByMonth(VILLAGE_ID, 2025, 3, USER_ID))
                .willReturn(new CalendarEventListResponse(List.of(annualRecurringEvent()), 2025, 3));

        mockMvc.perform(get(BASE, VILLAGE_ID)
                        .param("year", "2025")
                        .param("month", "3"))
                .andExpect(status().isOk())
                // エンベロープの year は問い合わせた年
                .andExpect(jsonPath("$.data.year").value(2025))
                // 一方、item の eventDate は登録年（2020）のまま返る。
                // FE は eventDate.year がクエリ年と一致する前提を置いてはならない。
                .andExpect(jsonPath("$.data.items[0].eventDate").value("2020-03-03"))
                .andExpect(jsonPath("$.data.items[0].isAnnualRecurring").value(true));
    }

    @Test
    @DisplayName("GET ?year=abc は 400（Integer バインド）")
    void listByMonth_invalidYear_returns400() throws Exception {
        mockMvc.perform(get(BASE, VILLAGE_ID).param("year", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        verify(calendarService, never()).listEventsByMonth(eq(VILLAGE_ID), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    // ==================================================================
    // イベント DTO の形状
    // ==================================================================

    @Test
    @DisplayName("GET /{eventId} — CalendarEventResponse の項目名を固定する")
    void getEvent_shape() throws Exception {
        given(calendarService.getEvent(VILLAGE_ID, EVENT_ID, USER_ID)).willReturn(annualRecurringEvent());

        mockMvc.perform(get(BASE + "/{eventId}", VILLAGE_ID, EVENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.villageId").value(VILLAGE_ID.toString()))
                .andExpect(jsonPath("$.data.title").value("桃の節句"))
                .andExpect(jsonPath("$.data.eventDate").value("2020-03-03"))
                // 単日イベントは eventEndDate = null（キー自体は出力される。グローバル NON_NULL は無効）
                .andExpect(jsonPath("$.data.eventEndDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.isAnnualRecurring").value(true))
                .andExpect(jsonPath("$.data.iconEmoji").value("🎎"))
                .andExpect(jsonPath("$.data.colorHex").value("#FFC0CB"))
                .andExpect(jsonPath("$.data.createdByUserId").value(USER_ID))
                // 別名は存在しない
                .andExpect(jsonPath("$.data.date").doesNotExist())
                .andExpect(jsonPath("$.data.recurring").doesNotExist())
                .andExpect(jsonPath("$.data.color").doesNotExist());
    }
}
