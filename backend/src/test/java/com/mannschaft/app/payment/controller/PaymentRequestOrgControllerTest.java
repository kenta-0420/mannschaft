package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.service.PaymentRequestService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PaymentRequestOrgController} 契約テスト（F08.9 P7 第二波）。
 *
 * <p>standaloneSetup + MockedStatic で {@code @WebMvcTest×@EnableMethodSecurity} 非互換を回避する
 * （P1 Wave5 の流儀）。発行 201 / 配信 / 取消 / 一覧（ページ・camelCase）/ 権原 403 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequestOrgController 契約テスト")
class PaymentRequestOrgControllerTest {

    private static final Long ORG_ID = 500L;
    private static final Long TEAM_ID = 600L;
    private static final Long OPERATOR_ID = 700L;

    @Mock private PaymentRequestService paymentRequestService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        PaymentRequestOrgController controller = new PaymentRequestOrgController(paymentRequestService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(OPERATOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private PaymentRequestEntity entity(PaymentRequestStatus status) {
        PaymentRequestEntity r = PaymentRequestEntity.builder()
                .organizationId(ORG_ID)
                .issuerScopeKind(ScopeKind.ORG)
                .issuerScopeId(ORG_ID)
                .payerScopeKind(ScopeKind.TEAM)
                .payerScopeId(TEAM_ID)
                .title("リーグ参加費")
                .faceAmount(30000)
                .currency("JPY")
                .dueDate(LocalDate.of(2026, 7, 31))
                .status(status)
                .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Test
    @DisplayName("発行: 201 Created・status=DRAFT・camelCase")
    void 発行201() throws Exception {
        given(paymentRequestService.create(eq(ORG_ID), eq(OPERATOR_ID), any()))
                .willReturn(entity(PaymentRequestStatus.DRAFT));

        String body = """
                {"payerTeamId":600,"title":"リーグ参加費","faceAmount":30000,"dueDate":"2026-07-31"}""";
        mockMvc.perform(post("/api/v1/organizations/{orgId}/payment-requests", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.payerScopeId").value(600))
                .andExpect(jsonPath("$.data.faceAmount").value(30000));
    }

    @Test
    @DisplayName("配信: SENT を返す")
    void 配信成功() throws Exception {
        UUID id = UUID.randomUUID();
        given(paymentRequestService.send(ORG_ID, id, OPERATOR_ID)).willReturn(entity(PaymentRequestStatus.SENT));

        mockMvc.perform(patch("/api/v1/organizations/{orgId}/payment-requests/{id}/send", ORG_ID, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));
    }

    @Test
    @DisplayName("取消: CANCELLED を返す")
    void 取消成功() throws Exception {
        UUID id = UUID.randomUUID();
        given(paymentRequestService.cancel(ORG_ID, id, OPERATOR_ID))
                .willReturn(entity(PaymentRequestStatus.CANCELLED));

        mockMvc.perform(patch("/api/v1/organizations/{orgId}/payment-requests/{id}/cancel", ORG_ID, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("一覧: ページ meta と camelCase content")
    void 一覧() throws Exception {
        Page<PaymentRequestEntity> page = new PageImpl<>(
                List.of(entity(PaymentRequestStatus.SENT)), Pageable.ofSize(50), 1);
        given(paymentRequestService.findForOrg(eq(ORG_ID), eq(OPERATOR_ID), any(), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/organizations/{orgId}/payment-requests", ORG_ID)
                        .param("status", "SENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SENT"))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    @DisplayName("権原なし: 発行で 403（MEMBERSHIP_BILLING_011）")
    void 権原なし403() throws Exception {
        given(paymentRequestService.create(anyLong(), anyLong(), any()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM));

        String body = """
                {"payerTeamId":600,"title":"x","faceAmount":1,"dueDate":"2026-07-31"}""";
        mockMvc.perform(post("/api/v1/organizations/{orgId}/payment-requests", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
