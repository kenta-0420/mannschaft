package com.mannschaft.app.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationTeamSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 予約設定（チームポリシー）更新 PATCH の入力検証テスト（F03.4 残ギャップMVP）。
 *
 * <p>StandaloneSetup + Mockito で Service 層をモック化し、Bean Validation / Jackson バインドの
 * 400 応答を実 HTTP 経路で検証する。{@code @PreAuthorize} は standaloneSetup では強制されないため
 * 403 はここでは扱わず、宣言レベルの検証は {@code ReservationControllerTest} の reflection テストに委ねる。</p>
 *
 * <p>手本: {@code com.mannschaft.app.event.controller.EventDelegationControllerTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("予約設定更新 入力検証テスト")
class ReservationSettingsValidationTest {

    @Mock
    private ReservationBusinessHourService businessHourService;
    @Mock
    private ReservationTeamSettingService teamSettingService;
    @Mock
    private ReservationPolicyService policyService;
    @Mock
    private com.mannschaft.app.reservation.service.ReservationSlotTemplateService templateService;
    /** F03.4.5 §6.3: pending_expire 設定変更の監査ログ記録用。 */
    @Mock
    private com.mannschaft.app.auth.service.AuditLogService auditLogService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long TEAM_ID = 10L;
    private static final String PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-settings";

    /** 監査ログの操作者解決（{@code SecurityUtils.getCurrentUserId()}）用。 */
    private static final Long ACTOR_USER_ID = 777L;

    /**
     * {@code SecurityUtils} の静的モック。
     *
     * <p>本テストは standaloneSetup（Spring Security フィルタ非搭載）のため SecurityContext が空で、
     * {@code SecurityUtils.getCurrentUserId()} が未認証として例外を投げ 401 になる。本番の
     * PATCH は {@code @PreAuthorize} 配下で必ず認証済みのため、テスト側で操作者を与えるのが正しい
     * （{@code ReservationControllerTest} と同じ {@code mockStatic} 作法）。認可そのものの実発火検証は
     * {@code ReservationAuthorizationEnforcementTest} が実 Security フィルタで担う。</p>
     */
    private org.mockito.MockedStatic<com.mannschaft.app.common.SecurityUtils> securityUtilsMock;

    @org.junit.jupiter.api.AfterEach
    void tearDownSecurityUtils() {
        if (securityUtilsMock != null) {
            securityUtilsMock.close();
        }
    }

    @BeforeEach
    void setUp() {
        securityUtilsMock = org.mockito.Mockito.mockStatic(com.mannschaft.app.common.SecurityUtils.class);
        securityUtilsMock.when(com.mannschaft.app.common.SecurityUtils::getCurrentUserId)
                .thenReturn(ACTOR_USER_ID);
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ReservationBusinessHourController controller = new ReservationBusinessHourController(
                businessHourService, teamSettingService, policyService, templateService, auditLogService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        // 正常系（getSettings の再取得）が呼ばれても落ちないよう既定 stub を用意（lenient）。
        lenient().when(businessHourService.hasBusinessHours(anyLong())).thenReturn(false);
        lenient().when(teamSettingService.getOrDefault(anyLong())).thenReturn(
                ReservationTeamSettingEntity.builder().teamId(TEAM_ID).build());
        lenient().when(policyService.getOrDefault(anyLong())).thenReturn(
                ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
    }

    @Test
    @DisplayName("不正な承認モード_400")
    void 不正な承認モード_400() throws Exception {
        // enum 以外の値は Jackson バインドで弾かれ 400。
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalMode\":\"INVALID_MODE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("キャンセル締切が範囲外_負数_400")
    void キャンセル締切_負数_400() throws Exception {
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelDeadlineHours\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("キャンセル締切が範囲外_上限超過_400")
    void キャンセル締切_上限超過_400() throws Exception {
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelDeadlineHours\":9000}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("remindBeforeHours_非数値CSV_400")
    void リマインドCSV_不正形式_400() throws Exception {
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remindBeforeHours\":\"24,abc\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("remindBeforeHours_ゼロ含む_400")
    void リマインドCSV_ゼロ_400() throws Exception {
        // 正の整数のみ許可（0 は不可）。
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remindBeforeHours\":\"0,1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("正常な部分更新_200")
    void 正常な部分更新_200() throws Exception {
        given(policyService.updatePolicy(anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willReturn(ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approvalMode\":\"MANUAL\",\"cancelDeadlineHours\":48,\"remindBeforeHours\":\"72,24,1\"}"))
                .andExpect(status().isOk());
    }

    // ========================================
    // 呼称設定（F03.4.5 §5）の Bean Validation
    // ========================================

    @Test
    @DisplayName("不正な呼称プリセット_400")
    void 不正な呼称プリセット_400() throws Exception {
        // enum 以外の値は Jackson バインドで弾かれ 400。
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceNameType\":\"INVALID_TYPE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("自由入力呼称_31文字_400")
    void 自由入力呼称_31文字_400() throws Exception {
        String tooLong = "あ".repeat(31);
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceNameType\":\"CUSTOM\",\"resourceNameCustom\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("自由入力呼称_30文字_境界正常_200")
    void 自由入力呼称_30文字_境界正常_200() throws Exception {
        String exactly30 = "あ".repeat(30);
        given(teamSettingService.updateResourceName(anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(ReservationTeamSettingEntity.builder().teamId(TEAM_ID).build());
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceNameType\":\"CUSTOM\",\"resourceNameCustom\":\"" + exactly30 + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("呼称プリセット_プリセット指定のみ_正常_200")
    void 呼称プリセット_プリセット指定のみ_正常_200() throws Exception {
        given(teamSettingService.updateResourceName(anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(ReservationTeamSettingEntity.builder().teamId(TEAM_ID).build());
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceNameType\":\"SEAT\"}"))
                .andExpect(status().isOk());
    }

    // ========================================
    // 仮押さえ自動失効設定（F03.4.5 §6.3・AC-6-1 / AC-6-16）
    // ========================================

    /**
     * 更新後のポリシーを stub する。
     *
     * <p>PATCH は更新後に {@code getSettings}（＝{@code policyService.getOrDefault}）で
     * 統合状態を読み直して返すため、{@code updatePolicy} と {@code getOrDefault} の両方を
     * 同じ結果で揃える必要がある（片方だけ stub すると応答は再取得側の値になる）。</p>
     */
    private void stubPolicyUpdate(Integer resultingPendingExpireHours) {
        ReservationPolicyEntity updated = ReservationPolicyEntity.builder()
                .teamId(TEAM_ID)
                .pendingExpireHours(resultingPendingExpireHours)
                .build();
        given(policyService.updatePolicy(anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .willReturn(updated);
        given(policyService.getOrDefault(anyLong())).willReturn(updated);
    }

    @Test
    @DisplayName("AC-6-1: pendingExpireHours=0 は範囲外で 400")
    void 仮押さえ失効時間_ゼロ_400() throws Exception {
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-6-1: pendingExpireHours=169 は範囲外で 400")
    void 仮押さえ失効時間_上限超過_400() throws Exception {
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":169}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-6-1(境界): pendingExpireHours=1 は成功する")
    void 仮押さえ失効時間_下限1_200() throws Exception {
        stubPolicyUpdate(1);
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExpireHours").value(1));
    }

    @Test
    @DisplayName("AC-6-1(境界): pendingExpireHours=168 は成功する")
    void 仮押さえ失効時間_上限168_200() throws Exception {
        stubPolicyUpdate(168);
        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":168}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExpireHours").value(168));
    }

    @Test
    @DisplayName("AC-6-1: clearPendingExpireHours=true で NULL 化され GET 応答にも反映される")
    void 仮押さえ失効_無効化() throws Exception {
        stubPolicyUpdate(null);
        // 更新後の再取得（getSettings）でも NULL が返るよう既定 stub を上書きする。
        given(policyService.getOrDefault(anyLong())).willReturn(
                ReservationPolicyEntity.builder().teamId(TEAM_ID).pendingExpireHours(null).build());

        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearPendingExpireHours\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExpireHours").doesNotExist());

        verify(policyService).updatePolicy(eq(TEAM_ID), isNull(), isNull(), isNull(), isNull(), eq(true));
    }

    @Test
    @DisplayName("AC-6-1: pendingExpireHours と clear の同時指定は clear を優先する（Service へそのまま両方渡す）")
    void 仮押さえ失効_両方指定はclear優先() throws Exception {
        stubPolicyUpdate(null);
        given(policyService.getOrDefault(anyLong())).willReturn(
                ReservationPolicyEntity.builder().teamId(TEAM_ID).pendingExpireHours(null).build());

        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":48,\"clearPendingExpireHours\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExpireHours").doesNotExist());

        // 優先ルール（clear 勝ち）は Service/Entity 側の単一実装が担う。Controller は素通しであること。
        verify(policyService).updatePolicy(eq(TEAM_ID), isNull(), isNull(), isNull(), eq(48), eq(true));
    }

    @Test
    @DisplayName("AC-6-1: GET 応答に pendingExpireHours が露出する")
    void 設定取得に仮押さえ失効時間が載る() throws Exception {
        given(policyService.getOrDefault(anyLong())).willReturn(
                ReservationPolicyEntity.builder().teamId(TEAM_ID).pendingExpireHours(36).build());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingExpireHours").value(36));
    }

    // ========================================
    // AC-6-16: 監査ログ
    // ========================================

    @Test
    @DisplayName("AC-6-16: pending_expire 設定変更が audit_logs に記録される")
    void 仮押さえ失効設定の変更が監査記録される() throws Exception {
        stubPolicyUpdate(48);

        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pendingExpireHours\":48}"))
                .andExpect(status().isOk());

        verify(auditLogService).record(
                eq("RESERVATION_PENDING_EXPIRE_SETTING_UPDATED"),
                eq(ACTOR_USER_ID), isNull(), eq(TEAM_ID), isNull(), isNull(), isNull(), isNull(),
                org.mockito.ArgumentMatchers.contains("48"));
    }

    @Test
    @DisplayName("AC-6-16: pending_expire を触らない更新では監査ログを書かない（ノイズを出さない）")
    void 仮押さえ失効を触らない更新は監査記録しない() throws Exception {
        stubPolicyUpdate(24);

        mockMvc.perform(patch(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cancelDeadlineHours\":48}"))
                .andExpect(status().isOk());

        verify(auditLogService, never()).record(
                eq("RESERVATION_PENDING_EXPIRE_SETTING_UPDATED"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }
}
