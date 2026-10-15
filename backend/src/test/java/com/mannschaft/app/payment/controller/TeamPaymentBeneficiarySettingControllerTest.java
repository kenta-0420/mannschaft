package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.entity.PaymentBeneficiarySettingEntity;
import com.mannschaft.app.payment.service.PaymentBeneficiarySettingService;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamPaymentBeneficiarySettingController} 契約テスト（F08.9 会費受益者制限・既定 ON）。
 *
 * <ul>
 *   <li><b>AC-S2</b>: GET は既定 true（会員のみ）を返す。</li>
 *   <li><b>AC-S3</b>: PUT で false↔true を更新し、更新後の値を返す。</li>
 *   <li><b>AC-S4</b>: 非 ADMIN の GET/PUT は 403。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPaymentBeneficiarySettingController 契約テスト")
class TeamPaymentBeneficiarySettingControllerTest {

    private static final Long TEAM_ID = 600L;
    private static final Long ACTOR_ID = 700L;

    @Mock private PaymentBeneficiarySettingService settingService;
    @Mock private AccessControlService accessControlService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        TeamPaymentBeneficiarySettingController controller =
                new TeamPaymentBeneficiarySettingController(settingService, accessControlService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("[AC-S2] GET: 設定行なし → 既定 true（会員のみ）を返す")
    void AC_S2_GETは既定true() throws Exception {
        given(settingService.isMemberOnly(TEAM_ID, null)).willReturn(true);

        mockMvc.perform(get("/api/v1/teams/{id}/payment-beneficiary-setting", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beneficiaryMemberOnly").value(true));
    }

    @Test
    @DisplayName("[AC-S3] PUT: false に更新 → 更新後の値(false)を返し service を呼ぶ")
    void AC_S3_PUTでfalseに更新() throws Exception {
        PaymentBeneficiarySettingEntity saved = PaymentBeneficiarySettingEntity.builder()
                .teamId(TEAM_ID).beneficiaryMemberOnly(false).build();
        given(settingService.updateSetting(eq(TEAM_ID), isNull(), eq(false))).willReturn(saved);

        mockMvc.perform(put("/api/v1/teams/{id}/payment-beneficiary-setting", TEAM_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"beneficiaryMemberOnly\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beneficiaryMemberOnly").value(false));

        verify(settingService).updateSetting(TEAM_ID, null, false);
    }

    @Test
    @DisplayName("[AC-S3] PUT: true に更新 → 更新後の値(true)を返す")
    void AC_S3_PUTでtrueに更新() throws Exception {
        PaymentBeneficiarySettingEntity saved = PaymentBeneficiarySettingEntity.builder()
                .teamId(TEAM_ID).beneficiaryMemberOnly(true).build();
        given(settingService.updateSetting(eq(TEAM_ID), isNull(), eq(true))).willReturn(saved);

        mockMvc.perform(put("/api/v1/teams/{id}/payment-beneficiary-setting", TEAM_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"beneficiaryMemberOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.beneficiaryMemberOnly").value(true));
    }

    @Test
    @DisplayName("[AC-S4] 非 ADMIN の GET は 403（checkAdminOrAbove が COMMON_002 を送出）")
    void AC_S4_非ADMINのGETは403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(ACTOR_ID, TEAM_ID, "TEAM");

        mockMvc.perform(get("/api/v1/teams/{id}/payment-beneficiary-setting", TEAM_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("[AC-S4] 非 ADMIN の PUT は 403")
    void AC_S4_非ADMINのPUTは403() throws Exception {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkAdminOrAbove(ACTOR_ID, TEAM_ID, "TEAM");

        mockMvc.perform(put("/api/v1/teams/{id}/payment-beneficiary-setting", TEAM_ID)
                        .contentType(APPLICATION_JSON)
                        .content("{\"beneficiaryMemberOnly\":false}"))
                .andExpect(status().isForbidden());
    }
}
