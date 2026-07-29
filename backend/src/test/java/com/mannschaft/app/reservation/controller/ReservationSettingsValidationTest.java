package com.mannschaft.app.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.service.ReservationBusinessHourService;
import com.mannschaft.app.reservation.service.ReservationPolicyService;
import com.mannschaft.app.reservation.service.ReservationTeamSettingService;
import com.mannschaft.app.reservation.service.ReservationViewAccessGuard;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

    @Mock
    private ReservationViewAccessGuard viewAccessGuard;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final String PATH = "/api/v1/teams/" + TEAM_ID + "/reservation-settings";

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ReservationBusinessHourController controller = new ReservationBusinessHourController(
                businessHourService, teamSettingService, policyService, templateService, viewAccessGuard);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        // standaloneSetup は Security フィルタチェーンを通さないが、getSettings（PATCH 応答再取得含む）が
        // 実 SecurityUtils.getCurrentUserId() を呼ぶため、SecurityContextHolder に直接認証情報を積む。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        // 正常系（getSettings の再取得）が呼ばれても落ちないよう既定 stub を用意（lenient）。
        lenient().when(businessHourService.hasBusinessHours(anyLong())).thenReturn(false);
        lenient().when(teamSettingService.getOrDefault(anyLong())).thenReturn(
                ReservationTeamSettingEntity.builder().teamId(TEAM_ID).build());
        lenient().when(policyService.getOrDefault(anyLong())).thenReturn(
                ReservationPolicyEntity.builder().teamId(TEAM_ID).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
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
}
