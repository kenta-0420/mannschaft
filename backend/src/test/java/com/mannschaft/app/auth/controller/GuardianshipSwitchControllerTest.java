package com.mannschaft.app.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.BlockedChildDto;
import com.mannschaft.app.auth.dto.IndependenceStatusResponse;
import com.mannschaft.app.auth.dto.SwitchableChildDto;
import com.mannschaft.app.auth.dto.SwitchableChildrenResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipHandoverService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GuardianshipSwitchController} 契約テスト（F08.9 P3a 切替可能な子の一覧 API）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>正常系: 200 OK / children・blockedChildren が camelCase 1:1 で返る</li>
 *   <li>viewer=自分: Service へ {@code SecurityUtils.getCurrentUserId()} の値が渡る（IDOR 防止）</li>
 *   <li>未認証: 401（COMMON_000）</li>
 * </ul>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * {@code @WebMvcTest + @EnableMethodSecurity} は SecurityConfig 全ロードを要求し失敗する（#1266 前科）。
 * 本テストは {@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} で
 * Controller のみを構成し Security コンテキストを回避する流儀を踏襲する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipSwitchController 契約テスト（F08.9 P3a）")
class GuardianshipSwitchControllerTest {

    private static final Long GUARDIAN_USER_ID = 100L;

    @Mock
    private GuardianshipSwitchService guardianshipSwitchService;
    @Mock
    private GuardianshipHandoverService guardianshipHandoverService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        // 本番（Spring Boot）と同様に LocalDate を ISO 文字列でシリアライズする（タイムスタンプ配列にしない）。
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MessageSource ms = new StaticMessageSource();
        GuardianshipSwitchController controller =
                new GuardianshipSwitchController(guardianshipSwitchService, guardianshipHandoverService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(GUARDIAN_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("正常系: 200 OK / camelCase / viewer=自分が Service に渡る")
    void list_ok_200_camelCase() throws Exception {
        SwitchableChildrenResponse serviceResponse = new SwitchableChildrenResponse(
                List.of(new SwitchableChildDto(11L, "小学生の子", "elementary", true)),
                List.of(new BlockedChildDto(12L, "中学生の子", "junior_high", false, "AGE_LOCKED")));
        given(guardianshipSwitchService.listSwitchableChildren(eq(GUARDIAN_USER_ID)))
                .willReturn(serviceResponse);

        mockMvc.perform(get("/api/v1/me/guardianship/switchable-children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.children[0].childUserId").value(11))
                .andExpect(jsonPath("$.data.children[0].displayName").value("小学生の子"))
                .andExpect(jsonPath("$.data.children[0].stageKey").value("elementary"))
                .andExpect(jsonPath("$.data.children[0].switchAllowed").value(true))
                .andExpect(jsonPath("$.data.blockedChildren[0].childUserId").value(12))
                .andExpect(jsonPath("$.data.blockedChildren[0].stageKey").value("junior_high"))
                .andExpect(jsonPath("$.data.blockedChildren[0].switchAllowed").value(false))
                .andExpect(jsonPath("$.data.blockedChildren[0].reason").value("AGE_LOCKED"));
    }

    @Test
    @DisplayName("子なし: children / blockedChildren とも空配列で 200")
    void list_empty_200() throws Exception {
        given(guardianshipSwitchService.listSwitchableChildren(eq(GUARDIAN_USER_ID)))
                .willReturn(new SwitchableChildrenResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/me/guardianship/switchable-children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.children").isEmpty())
                .andExpect(jsonPath("$.data.blockedChildren").isEmpty());
    }

    @Test
    @DisplayName("未認証: 401（COMMON_000）")
    void list_unauthenticated_401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new com.mannschaft.app.common.BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/me/guardianship/switchable-children"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }

    // ========================================
    // POST /switch — 切替開始（F08.9 P3c）
    // ========================================

    @Test
    @DisplayName("POST 正常系: 204 / Service へ (guardian, child) が渡る")
    void startSwitch_204() throws Exception {
        mockMvc.perform(post("/api/v1/me/guardianship/switch")
                        .contentType("application/json")
                        .content("{\"childUserId\":11}"))
                .andExpect(status().isNoContent());
        verify(guardianshipSwitchService).startSwitch(GUARDIAN_USER_ID, 11L);
    }

    @Test
    @DisplayName("POST 年齢封印: 403 GUARDIANSHIP_SWITCH_AGE_LOCKED（MEMBERSHIP_BILLING_004）")
    void startSwitch_ageLocked_403() throws Exception {
        willThrow(new com.mannschaft.app.common.BusinessException(
                com.mannschaft.app.payment.MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED))
                .given(guardianshipSwitchService).startSwitch(GUARDIAN_USER_ID, 11L);

        mockMvc.perform(post("/api/v1/me/guardianship/switch")
                        .contentType("application/json")
                        .content("{\"childUserId\":11}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_004"));
    }

    @Test
    @DisplayName("POST リンクなし: 403 GUARDIANSHIP_LINK_NOT_FOUND（MEMBERSHIP_BILLING_005）")
    void startSwitch_linkNotFound_403() throws Exception {
        willThrow(new com.mannschaft.app.common.BusinessException(
                com.mannschaft.app.payment.MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND))
                .given(guardianshipSwitchService).startSwitch(GUARDIAN_USER_ID, 11L);

        mockMvc.perform(post("/api/v1/me/guardianship/switch")
                        .contentType("application/json")
                        .content("{\"childUserId\":11}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
    }

    @Test
    @DisplayName("POST childUserId 欠落: 400（バリデーション）")
    void startSwitch_missingChildId_400() throws Exception {
        mockMvc.perform(post("/api/v1/me/guardianship/switch")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ========================================
    // DELETE /switch — 切替終了（F08.9 P3c）
    // ========================================

    @Test
    @DisplayName("DELETE 正常系: 204 / Service へ (guardian, child) が渡る")
    void endSwitch_204() throws Exception {
        mockMvc.perform(delete("/api/v1/me/guardianship/switch")
                        .param("childUserId", "11"))
                .andExpect(status().isNoContent());
        verify(guardianshipSwitchService).endSwitch(GUARDIAN_USER_ID, 11L);
    }

    // ========================================
    // GET /children/{id}/independence-status（F08.9 P3c-2）
    // ========================================

    @Test
    @DisplayName("GET independence-status 正常系: 200 / camelCase / sealDate を含む")
    void independenceStatus_200() throws Exception {
        given(guardianshipSwitchService.getIndependenceStatus(eq(GUARDIAN_USER_ID), eq(11L)))
                .willReturn(new IndependenceStatusResponse(
                        11L, "elementary", true, java.time.LocalDate.parse("2027-04-01"), false));

        mockMvc.perform(get("/api/v1/me/guardianship/children/11/independence-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.childUserId").value(11))
                .andExpect(jsonPath("$.data.stageKey").value("elementary"))
                .andExpect(jsonPath("$.data.switchAllowed").value(true))
                .andExpect(jsonPath("$.data.sealDate").value("2027-04-01"))
                .andExpect(jsonPath("$.data.passwordSet").value(false));
    }

    @Test
    @DisplayName("GET independence-status IDOR: 403 GUARDIANSHIP_LINK_NOT_FOUND（MEMBERSHIP_BILLING_005）")
    void independenceStatus_idor_403() throws Exception {
        given(guardianshipSwitchService.getIndependenceStatus(eq(GUARDIAN_USER_ID), eq(11L)))
                .willThrow(new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.payment.MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND));

        mockMvc.perform(get("/api/v1/me/guardianship/children/11/independence-status"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_005"));
    }

    // ========================================
    // POST /children/{id}/handover/initiate（F08.9 P3c-2）
    // ========================================

    @Test
    @DisplayName("POST handover 正常系（body 省略）: 204 / Service へ (guardian, child, null) が渡る")
    void handover_noBody_204() throws Exception {
        mockMvc.perform(post("/api/v1/me/guardianship/children/11/handover/initiate"))
                .andExpect(status().isNoContent());
        verify(guardianshipHandoverService)
                .initiateHandover(eq(GUARDIAN_USER_ID), eq(11L), org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST handover 正常系（childEmail 指定）: 204 / Service へメールが渡る")
    void handover_withEmail_204() throws Exception {
        mockMvc.perform(post("/api/v1/me/guardianship/children/11/handover/initiate")
                        .contentType("application/json")
                        .content("{\"childEmail\":\"new-child@example.com\"}"))
                .andExpect(status().isNoContent());
        verify(guardianshipHandoverService)
                .initiateHandover(eq(GUARDIAN_USER_ID), eq(11L), eq("new-child@example.com"),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("POST handover メール必須エラー: 400 MEMBERSHIP_BILLING_006")
    void handover_emailRequired_400() throws Exception {
        org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.payment.MembershipBillingErrorCode.GUARDIANSHIP_HANDOVER_EMAIL_REQUIRED))
                .given(guardianshipHandoverService)
                .initiateHandover(eq(GUARDIAN_USER_ID), eq(11L), org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(post("/api/v1/me/guardianship/children/11/handover/initiate"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_006"));
    }

    @Test
    @DisplayName("POST handover acting-as 中: 403 MEMBERSHIP_BILLING_003")
    void handover_actingAs_403() throws Exception {
        org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                        com.mannschaft.app.payment.MembershipBillingErrorCode.MEMBERSHIP_AUTHENTICATION_CRITICAL_OPERATION))
                .given(guardianshipHandoverService)
                .initiateHandover(eq(GUARDIAN_USER_ID), eq(11L), org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.anyString());

        mockMvc.perform(post("/api/v1/me/guardianship/children/11/handover/initiate"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("MEMBERSHIP_BILLING_003"));
    }
}
