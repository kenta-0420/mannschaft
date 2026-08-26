package com.mannschaft.app.payment.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.admin.dto.FeePolicyResponse;
import com.mannschaft.app.payment.admin.dto.FeePolicyUpsertRequest;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
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
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F22.1 統一決済 R2: {@link SystemAdminFeePolicyController} 契約テスト（test-first）。
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@link GlobalExceptionHandler} で Controller・例外変換のみを構成
 * （{@code @WebMvcTest + @EnableMethodSecurity} 非互換の回避・既存 PaymentCheckoutControllerTest 同型）。
 * 認可（SYSTEM_ADMIN 以外 403 / 未認証 401）はパス {@code /api/v1/system-admin/**} 単位で
 * {@code SecurityConfigAuthorizationTest} が担保する（本テストはステータス/レスポンス/エラーコード契約を検証）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminFeePolicyController 契約テスト")
class SystemAdminFeePolicyControllerTest {

    @Mock
    private FeePolicyAdminService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private FeePolicyResponse sample(String key, boolean enabled) {
        return FeePolicyResponse.builder()
                .policyKey(key)
                .displayName(key + " 表示")
                .percentRate(new BigDecimal("0.0300"))
                .flatFeeMinor(100L)
                .enabled(enabled)
                .description("desc")
                .assignmentCount(1L)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private FeePolicyUpsertRequest reqBody(String key, BigDecimal rate, Long flat) {
        FeePolicyUpsertRequest r = new FeePolicyUpsertRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "policyKey", key);
        org.springframework.test.util.ReflectionTestUtils.setField(r, "displayName", key + " 表示");
        org.springframework.test.util.ReflectionTestUtils.setField(r, "percentRate", rate);
        org.springframework.test.util.ReflectionTestUtils.setField(r, "flatFeeMinor", flat);
        return r;
    }

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        SystemAdminFeePolicyController controller = new SystemAdminFeePolicyController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(9L);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("一覧: 200・camelCase で全件")
    void list_200() throws Exception {
        given(service.listPolicies()).willReturn(List.of(sample("DEFAULT", true), sample("RECRUITMENT_HELPER", false)));
        mockMvc.perform(get("/api/v1/system-admin/fee-policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].policyKey").value("DEFAULT"))
                .andExpect(jsonPath("$.data[0].flatFeeMinor").value(100))
                .andExpect(jsonPath("$.data[1].enabled").value(false));
    }

    @Test
    @DisplayName("単件: 200・camelCase")
    void get_200() throws Exception {
        given(service.getPolicy("RECRUITMENT_HELPER")).willReturn(sample("RECRUITMENT_HELPER", true));
        mockMvc.perform(get("/api/v1/system-admin/fee-policies/{k}", "RECRUITMENT_HELPER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyKey").value("RECRUITMENT_HELPER"))
                .andExpect(jsonPath("$.data.percentRate").value(0.03))
                .andExpect(jsonPath("$.data.assignmentCount").value(1));
    }

    @Test
    @DisplayName("単件: 不在は 404（PAYMENT_C051）")
    void get_404() throws Exception {
        given(service.getPolicy("NOPE"))
                .willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND));
        mockMvc.perform(get("/api/v1/system-admin/fee-policies/{k}", "NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C051"));
    }

    @Test
    @DisplayName("作成: 201・camelCase")
    void create_201() throws Exception {
        given(service.createPolicy(any(), eq(9L))).willReturn(sample("RECRUITMENT_HELPER", true));
        mockMvc.perform(post("/api/v1/system-admin/fee-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.policyKey").value("RECRUITMENT_HELPER"));
    }

    @Test
    @DisplayName("作成: Bean Validation 違反（不正キー）は 400")
    void create_invalidKey_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/fee-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("bad-key", new BigDecimal("0.03"), 100L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("作成: Bean Validation 違反（率1以上）は 400")
    void create_rateOutOfRange_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/fee-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("OK_KEY", new BigDecimal("1.5"), 100L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("作成: 重複は 409（PAYMENT_C054）")
    void create_duplicate_409() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ALREADY_EXISTS))
                .given(service).createPolicy(any(), eq(9L));
        mockMvc.perform(post("/api/v1/system-admin/fee-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("DEFAULT", new BigDecimal("0.05"), 0L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C054"));
    }

    @Test
    @DisplayName("作成: 業務制約（率・固定額ゼロ）は 422（PAYMENT_C053）")
    void create_zeroFee_422() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE))
                .given(service).createPolicy(any(), eq(9L));
        mockMvc.perform(post("/api/v1/system-admin/fee-policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("ZERO", new BigDecimal("0.0"), 0L))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C053"));
    }

    @Test
    @DisplayName("更新: 200")
    void update_200() throws Exception {
        given(service.updatePolicy(eq("RECRUITMENT_HELPER"), any(), eq(9L)))
                .willReturn(sample("RECRUITMENT_HELPER", true));
        mockMvc.perform(put("/api/v1/system-admin/fee-policies/{k}", "RECRUITMENT_HELPER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.policyKey").value("RECRUITMENT_HELPER"));
    }

    @Test
    @DisplayName("無効化: 204")
    void disable_204() throws Exception {
        mockMvc.perform(delete("/api/v1/system-admin/fee-policies/{k}", "RECRUITMENT_HELPER"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("無効化: DEFAULT は 409（PAYMENT_C052）")
    void disable_default_409() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_DEFAULT_IMMUTABLE))
                .given(service).disablePolicy(eq("DEFAULT"), eq(9L));
        mockMvc.perform(delete("/api/v1/system-admin/fee-policies/{k}", "DEFAULT"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C052"));
    }
}
