package com.mannschaft.app.config.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mannschaft.app.auth.controller.GuardianChildViewController;
import com.mannschaft.app.auth.guardianship.GuardianChildViewService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import com.mannschaft.app.config.OrgScopeIdConverter;
import com.mannschaft.app.config.ScopeSlugIdConverter;
import com.mannschaft.app.config.TeamScopeIdConverter;
import com.mannschaft.app.config.WebMvcConfig;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.format.support.FormattingConversionService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * クエリパラメータ（{@code @RequestParam}）で受け取る {@link LocalDateTime} の
 * タイムゾーン正規化を検証する（Issue #2508 Phase 1）。
 *
 * <h2>何を確かめているか</h2>
 *
 * <p>リクエストボディ側は {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer}
 * が「オフセット付きなら瞬間として解釈 → JST 壁時計へ変換」「オフセット無しなら
 * {@link TimezoneContextHolder#isResolved()} が真のときだけユーザー TZ の壁時計として解釈」
 * という規則を実装している。クエリパラメータは Jackson を通らず Spring の ConversionService を
 * 通るため、<b>同じ日時をボディで送るかクエリで送るかで解釈が変わらないこと</b>が本テストの主題である。</p>
 *
 * <p>期待値はすべて上記デシリアライザの規則から導いており、ボディ側の規則と一対一に対応する。</p>
 *
 * <h2>構成</h2>
 *
 * <p>{@link WebMvcConfig#addFormatters} が組み立てる {@link FormattingConversionService} を
 * standalone な MockMvc に食わせることで、<b>本番と同じ登録経路</b>を通したうえで
 * {@link QueryParamDateTimeProbeController} に届いた値を観測する。
 * DB もアプリケーションコンテキストも要らない。</p>
 *
 * <p>{@link TimezoneContextHolder} は ThreadLocal なので、各テスト後に必ず
 * {@code clear()} する（他テストへの汚染防止）。</p>
 */
@DisplayName("クエリパラメータの LocalDateTime タイムゾーン正規化（Issue #2508 Phase 1）")
class LocalDateTimeQueryParamBindingTest {

    private static final String PROBE = "/__test__/query-param-datetime";
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    /** LA の 2027-03-15T10:30（PDT / −07:00）に対応する JST 壁時計。 */
    private static final String LA_1030_IN_JST = "2027-03-16T02:30";

    private MockMvc mockMvc;

    /**
     * 本番の登録経路（{@link WebMvcConfig#addFormatters}）を通した ConversionService を組み立てる。
     *
     * <p>スコープ slug 変換器は本題と無関係なので mock を渡す。ただし
     * {@link ScopeSlugIdConverter} は {@code String→Long} を担うため、素の mock のままだと
     * 数値のパス変数まで巻き込んで潰してしまう。数値をそのまま {@link Long} にする最小の振る舞いだけ与える。</p>
     *
     * <p>ここを {@code new DefaultFormattingConversionService()} だけで済ませてしまうと
     * 「本番に登録されているか」を確かめられなくなるため、必ず {@link WebMvcConfig} を経由させる。</p>
     */
    private static FormattingConversionService productionConversionService() {
        FormattingConversionService registry = new DefaultFormattingConversionService();
        ScopeSlugIdConverter scopeSlugIdConverter = Mockito.mock(ScopeSlugIdConverter.class);
        Mockito.when(scopeSlugIdConverter.convert(Mockito.anyString()))
                .thenAnswer(invocation -> Long.valueOf(invocation.getArgument(0)));
        new WebMvcConfig(
                scopeSlugIdConverter,
                Mockito.mock(OrgScopeIdConverter.class),
                Mockito.mock(TeamScopeIdConverter.class))
                .addFormatters(registry);
        return registry;
    }

    @BeforeEach
    void setUp() {
        MessageSource messageSource = new StaticMessageSource();
        mockMvc = MockMvcBuilders.standaloneSetup(new QueryParamDateTimeProbeController())
                .setConversionService(productionConversionService())
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
    }

    @AfterEach
    void tearDown() {
        TimezoneContextHolder.clear();
    }

    private String bound(String path, String value) throws Exception {
        return mockMvc.perform(get(PROBE + path).param("value", value))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    // ================================================================
    // AC-1 / AC-2 / AC-11: オフセット付き入力
    // ================================================================

    @Test
    @DisplayName("AC-1: LA ユーザーのオフセット付き入力（A 種別）が JST 壁時計へ正規化される")
    void ac1_isoDateTime_withOffset_normalizedToJst() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/iso", "2027-03-15T10:30:00-07:00")).isEqualTo(LA_1030_IN_JST);
    }

    @Test
    @DisplayName("AC-2: JST ユーザーの +09:00 入力は恒等（既存挙動を変えない）")
    void ac2_isoDateTime_tokyoOffset_isIdentity() throws Exception {
        TimezoneContextHolder.setResolved(TOKYO);

        assertThat(bound("/iso", "2027-03-15T10:30:00+09:00")).isEqualTo("2027-03-15T10:30");
    }

    @Test
    @DisplayName("AC-11: Z サフィックス（UTC 瞬間）が JST 壁時計へ正規化される")
    void ac11_zuluSuffix_normalizedToJst() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/iso", "2027-03-15T01:30:00Z")).isEqualTo("2027-03-15T10:30");
    }

    // ================================================================
    // AC-3 / AC-4: A 種別以外の受け方でも同じ規則が適用される
    // ================================================================

    @Test
    @DisplayName("AC-3: アノテーション無し（B 種別）でもオフセット付き入力を受理し JST へ正規化する")
    void ac3_plainParam_withOffset_normalizedToJst() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/plain", "2027-03-15T10:30:00-07:00")).isEqualTo(LA_1030_IN_JST);
    }

    @Test
    @DisplayName("AC-4: pattern 指定（C 種別）でもオフセット付き入力を受理し JST へ正規化する")
    void ac4_patternParam_withOffset_normalizedToJst() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/pattern", "2027-03-15T10:30:00-07:00")).isEqualTo(LA_1030_IN_JST);
    }

    // ================================================================
    // AC-5 / AC-6: オフセット無し入力
    // ================================================================

    @Test
    @DisplayName("AC-5: オフセット無し入力は解決済みユーザー TZ の壁時計として解釈され JST へ変換される")
    void ac5_withoutOffset_resolvedUserZone_interpretedAsUserWallClock() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/iso", "2027-03-15T10:30:00")).isEqualTo(LA_1030_IN_JST);
    }

    @Test
    @DisplayName("AC-6: 未解決（未認証・バッチ）のオフセット無し入力はサーバー既定 TZ の壁時計として恒等に通る")
    void ac6_withoutOffset_unresolved_fallsBackToServerZone() throws Exception {
        // TimezoneContextHolder には何も積まない（= isResolved() が false）

        assertThat(TimezoneContextHolder.isResolved()).isFalse();
        assertThat(bound("/iso", "2027-03-15T10:30:00")).isEqualTo("2027-03-15T10:30");
        assertThat(bound("/plain", "2027-03-15T10:30:00")).isEqualTo("2027-03-15T10:30");
        assertThat(bound("/pattern", "2027-03-15T10:30:00")).isEqualTo("2027-03-15T10:30");
    }

    @Test
    @DisplayName("AC-6: set()（解決済みの印なし）で UTC が積まれていてもサーバー既定 TZ として扱う")
    void ac6_withoutOffset_setButNotResolved_fallsBackToServerZone() throws Exception {
        TimezoneContextHolder.set(ZoneId.of("UTC"));

        assertThat(bound("/iso", "2027-03-15T10:30:00")).isEqualTo("2027-03-15T10:30");
    }

    // ================================================================
    // AC-7: 解釈不能な入力
    // ================================================================

    @Test
    @DisplayName("AC-7: 解釈不能な文字列は 400（500 に化けない）")
    void ac7_unparseableInput_returns400() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        for (String path : List.of("/iso", "/plain", "/pattern")) {
            for (String bad : List.of("not-a-date", "2027-13-45T99:99:99")) {
                mockMvc.perform(get(PROBE + path).param("value", bad))
                        .andExpect(status().isBadRequest());
            }
        }
    }

    // ================================================================
    // AC-13: 値域超過（DateTimeException / ArithmeticException）は 400（500 に化けない）
    // ================================================================

    @Test
    @DisplayName("AC-13: 値域超過のオフセット付き入力は 400（DateTimeException が 500 に化けない）")
    void ac13_outOfRangeOffsetInput_returns400NotServerError() throws Exception {
        TimezoneContextHolder.setResolved(TOKYO);

        for (String path : List.of("/iso", "/plain", "/pattern")) {
            // OffsetDateTime.parse 自体は成功するが、atZoneSameInstant().toLocalDateTime() で
            // EpochDay 値域超過の DateTimeException を投げる極端値
            mockMvc.perform(get(PROBE + path).param("value", "+999999999-12-31T23:59:59-18:00"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get(PROBE + path).param("value", "-999999999-01-01T00:00:00+18:00"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ================================================================
    // AC-8: 範囲検索の両端 inclusive
    // ================================================================

    @Test
    @DisplayName("AC-8: 範囲検索は from ちょうど・to ちょうどのレコードを含む（両端 inclusive）")
    void ac8_rangeSearch_bothEndsInclusive() throws Exception {
        TimezoneContextHolder.setResolved(TOKYO);

        MvcResult result = mockMvc.perform(get(PROBE + "/range")
                        .param("from", "2027-03-15T00:00:00")
                        .param("to", "2027-03-15T23:59:59"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo(
                "2027-03-15T00:00,2027-03-15T12:00,2027-03-15T23:59:59");
    }

    @Test
    @DisplayName("AC-8: オフセット付きで同じ範囲を指定しても両端 inclusive の結果が一致する")
    void ac8_rangeSearch_withOffset_sameInclusiveResult() throws Exception {
        TimezoneContextHolder.setResolved(TOKYO);

        MvcResult result = mockMvc.perform(get(PROBE + "/range")
                        .param("from", "2027-03-15T00:00:00+09:00")
                        .param("to", "2027-03-15T23:59:59+09:00"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo(
                "2027-03-15T00:00,2027-03-15T12:00,2027-03-15T23:59:59");
    }

    // ================================================================
    // AC-12: LocalDate の受け口は影響を受けない
    // ================================================================

    @Test
    @DisplayName("AC-12: LocalDate パラメータはユーザー TZ に関係なく入力どおりに束縛される")
    void ac12_localDateParam_unaffected() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        assertThat(bound("/date-iso", "2027-03-15")).isEqualTo("2027-03-15");
        assertThat(bound("/date-plain", "2027-03-15")).isEqualTo("2027-03-15");
    }

    @Test
    @DisplayName("AC-12: LocalDate パラメータの不正値は従来どおり 400")
    void ac12_localDateParam_invalid_returns400() throws Exception {
        TimezoneContextHolder.setResolved(LOS_ANGELES);

        mockMvc.perform(get(PROBE + "/date-iso").param("value", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 実在エンドポイント（{@link GuardianChildViewController}）に対する検証。
     *
     * <p>観測用コントローラだけでは「本番の受け口でも同じことが起きる」ことを保証できないため、
     * 日時レンジをクエリで受ける実在の GET エンドポイントを 1 本使って、
     * 逆順レンジのステータスと認可応答を固定する。</p>
     */
    @Nested
    @DisplayName("実在エンドポイントでの挙動（GuardianChildViewController）")
    class RealEndpoint {

        private static final Long GUARDIAN_USER_ID = 100L;
        private static final String BASE = "/api/v1/me/guardianship/children/11";

        private MockMvc realMockMvc;
        private GuardianChildViewService guardianChildViewService;
        private MockedStatic<SecurityUtils> securityUtilsMock;

        @BeforeEach
        void setUpReal() {
            guardianChildViewService = Mockito.mock(GuardianChildViewService.class);
            realMockMvc = MockMvcBuilders
                    .standaloneSetup(new GuardianChildViewController(guardianChildViewService))
                    .setConversionService(productionConversionService())
                    .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                    .build();

            securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
            securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(GUARDIAN_USER_ID);
        }

        @AfterEach
        void tearDownReal() {
            securityUtilsMock.close();
            TimezoneContextHolder.clear();
        }

        @Test
        @DisplayName("AC-9: from > to（逆順レンジ）は 200 / 0 件（サービス層で弾かれない現状挙動を固定）")
        void ac9_reversedRange_returns200EmptyList() throws Exception {
            TimezoneContextHolder.setResolved(TOKYO);
            given(guardianChildViewService.getChildSchedules(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                    .willReturn(List.of());

            realMockMvc.perform(get(BASE + "/schedules")
                            .param("from", "2027-03-31T23:59:59")
                            .param("to", "2027-03-01T00:00:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("AC-10: オフセット付きレンジでも保護者リンク非該当は 403 のまま（認可は緩まない）")
        void ac10_authorizationStillEnforced_withOffsetRange() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            given(guardianChildViewService.getChildSchedules(eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                    .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND));

            realMockMvc.perform(get(BASE + "/schedules")
                            .param("from", "2027-03-01T00:00:00-08:00")
                            .param("to", "2027-03-31T23:59:59-07:00"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
        }

        @Test
        @DisplayName("AC-10: オフセット付きレンジでも年齢封印は 403 のまま")
        void ac10_ageLockStillEnforced_withOffsetRange() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            given(guardianChildViewService.getChildAttendanceStats(
                    eq(GUARDIAN_USER_ID), eq(11L), any(), any()))
                    .willThrow(new BusinessException(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED));

            realMockMvc.perform(get(BASE + "/attendance/stats")
                            .param("from", "2027-03-01T00:00:00Z")
                            .param("to", "2027-03-31T23:59:59Z"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_004"));
        }

        @Test
        @DisplayName("AC-1 実在経路: LA ユーザーのオフセット付きレンジが JST 壁時計でサービスへ渡る")
        void ac1_realEndpoint_passesJstWallClockToService() throws Exception {
            TimezoneContextHolder.setResolved(LOS_ANGELES);
            given(guardianChildViewService.getChildSchedules(
                    eq(GUARDIAN_USER_ID), eq(11L),
                    eq(LocalDateTime.parse("2027-03-16T02:30")),
                    eq(LocalDateTime.parse("2027-03-16T03:30"))))
                    .willReturn(List.of());

            realMockMvc.perform(get(BASE + "/schedules")
                            .param("from", "2027-03-15T10:30:00-07:00")
                            .param("to", "2027-03-15T11:30:00-07:00"))
                    .andExpect(status().isOk());

            Mockito.verify(guardianChildViewService).getChildSchedules(
                    GUARDIAN_USER_ID, 11L,
                    LocalDateTime.parse("2027-03-16T02:30"),
                    LocalDateTime.parse("2027-03-16T03:30"));
        }
    }
}
