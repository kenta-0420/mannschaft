package com.mannschaft.app.payment.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.admin.dto.FeePolicyAssignmentResponse;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F22.1 統一決済 R2: {@link SystemAdminFeePolicyAssignmentController} 契約テスト（test-first）。
 *
 * <p>standaloneSetup + GlobalExceptionHandler で Controller・例外変換のみを構成。
 * 認可（SYSTEM_ADMIN 以外 403 / 未認証 401）はパス単位で {@code SecurityConfigAuthorizationTest} が担保。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SystemAdminFeePolicyAssignmentController 契約テスト")
class SystemAdminFeePolicyAssignmentControllerTest {

    @Mock
    private FeePolicyAdminService service;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final UUID ID = UUID.fromString("019607a0-0000-7000-8000-000000000001");

    private FeePolicyAssignmentResponse sample() {
        return FeePolicyAssignmentResponse.builder()
                .id(ID)
                .sourceKind("RECRUITMENT")
                .subKey("helper")
                .policyKey("RECRUITMENT_HELPER")
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private String body(String sourceKind, String subKey, String policyKey) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("sourceKind", sourceKind);
            put("subKey", subKey);
            put("policyKey", policyKey);
        }});
    }

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        SystemAdminFeePolicyAssignmentController controller = new SystemAdminFeePolicyAssignmentController(service);
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
    @DisplayName("一覧: 200・camelCase")
    void list_200() throws Exception {
        given(service.listAssignments()).willReturn(List.of(sample()));
        mockMvc.perform(get("/api/v1/system-admin/fee-policy-assignments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceKind").value("RECRUITMENT"))
                .andExpect(jsonPath("$.data[0].subKey").value("helper"))
                .andExpect(jsonPath("$.data[0].policyKey").value("RECRUITMENT_HELPER"));
    }

    @Test
    @DisplayName("作成: 201・camelCase")
    void create_201() throws Exception {
        given(service.createAssignment(any(), eq(9L))).willReturn(sample());
        mockMvc.perform(post("/api/v1/system-admin/fee-policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECRUITMENT", "helper", "RECRUITMENT_HELPER")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(ID.toString()))
                .andExpect(jsonPath("$.data.policyKey").value("RECRUITMENT_HELPER"));
    }

    @Test
    @DisplayName("作成: 参照先 policy 不在は 404（PAYMENT_C051）")
    void create_policyNotFound_404() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND))
                .given(service).createAssignment(any(), eq(9L));
        mockMvc.perform(post("/api/v1/system-admin/fee-policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECRUITMENT", "helper", "NOPE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C051"));
    }

    @Test
    @DisplayName("作成: 参照先 policy 無効は 422（PAYMENT_C056）")
    void create_policyDisabled_422() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_POLICY_DISABLED))
                .given(service).createAssignment(any(), eq(9L));
        mockMvc.perform(post("/api/v1/system-admin/fee-policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECRUITMENT", "helper", "OLD")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C056"));
    }

    @Test
    @DisplayName("作成: UNIQUE 違反（重複）は 409（PAYMENT_C055）")
    void create_duplicate_409() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_DUPLICATE))
                .given(service).createAssignment(any(), eq(9L));
        mockMvc.perform(post("/api/v1/system-admin/fee-policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECRUITMENT", "helper", "RECRUITMENT_HELPER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C055"));
    }

    @Test
    @DisplayName("作成: Bean Validation 違反（policyKey 不正形式）は 400")
    void create_invalidPolicyKey_400() throws Exception {
        mockMvc.perform(post("/api/v1/system-admin/fee-policy-assignments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("RECRUITMENT", "helper", "bad-key")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("解除: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/system-admin/fee-policy-assignments/{id}", ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("解除: 不在は 404（PAYMENT_C002）")
    void delete_notFound_404() throws Exception {
        willThrow(new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND))
                .given(service).deleteAssignment(eq(ID), eq(9L));
        mockMvc.perform(delete("/api/v1/system-admin/fee-policy-assignments/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_C002"));
    }
}
