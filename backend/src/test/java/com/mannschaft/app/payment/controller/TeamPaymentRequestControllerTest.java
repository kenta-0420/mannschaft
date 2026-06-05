package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.service.PaymentRequestPayResult;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamPaymentRequestController} 契約テスト（F08.9 P7 第二波）。
 *
 * <p>受信一覧 / 詳細（VIEWED 遷移を service 委譲で確認）/ 支払い（Idempotency-Key ヘッダ橋渡し）/
 * IDOR 403 を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPaymentRequestController 契約テスト")
class TeamPaymentRequestControllerTest {

    private static final Long TEAM_ID = 600L;
    private static final Long OTHER_TEAM_ID = 999L;
    private static final Long ACTOR_ID = 700L;

    @Mock private PaymentRequestService paymentRequestService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        TeamPaymentRequestController controller = new TeamPaymentRequestController(paymentRequestService);
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

    private PaymentRequestEntity entity(PaymentRequestStatus status) {
        PaymentRequestEntity r = PaymentRequestEntity.builder()
                .organizationId(500L)
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
    @DisplayName("受信一覧: camelCase content")
    void 受信一覧() throws Exception {
        given(paymentRequestService.findForTeam(TEAM_ID, ACTOR_ID))
                .willReturn(List.of(entity(PaymentRequestStatus.SENT)));

        mockMvc.perform(get("/api/v1/teams/{teamId}/payment-requests", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SENT"))
                .andExpect(jsonPath("$.data[0].payerScopeId").value(600));
    }

    @Test
    @DisplayName("詳細: 初閲覧で service.viewByTeam に委譲し VIEWED を返す")
    void 詳細でVIEWED() throws Exception {
        UUID id = UUID.randomUUID();
        given(paymentRequestService.viewByTeam(TEAM_ID, id, ACTOR_ID))
                .willReturn(entity(PaymentRequestStatus.VIEWED));

        mockMvc.perform(get("/api/v1/teams/{teamId}/payment-requests/{id}", TEAM_ID, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VIEWED"));
        verify(paymentRequestService).viewByTeam(TEAM_ID, id, ACTOR_ID);
    }

    @Test
    @DisplayName("支払い: Idempotency-Key ヘッダを service へ橋渡しし clientSecret を返す")
    void 支払い() throws Exception {
        UUID id = UUID.randomUUID();
        UUID escrow = UUID.randomUUID();
        UUID advance = UUID.randomUUID();
        given(paymentRequestService.pay(eq(TEAM_ID), eq(id), eq(ACTOR_ID), eq("idem-key-xyz")))
                .willReturn(new PaymentRequestPayResult(id, escrow, advance, "cs_secret"));

        mockMvc.perform(post("/api/v1/teams/{teamId}/payment-requests/{id}/pay", TEAM_ID, id)
                        .header("Idempotency-Key", "idem-key-xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientSecret").value("cs_secret"))
                .andExpect(jsonPath("$.data.advanceId").value(advance.toString()));
        verify(paymentRequestService).pay(TEAM_ID, id, ACTOR_ID, "idem-key-xyz");
    }

    @Test
    @DisplayName("IDOR: 他チーム宛て支払いは 403（NOT_FOR_THIS_TEAM）")
    void 他チーム支払い403() throws Exception {
        UUID id = UUID.randomUUID();
        given(paymentRequestService.pay(eq(OTHER_TEAM_ID), eq(id), eq(ACTOR_ID), any()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM));

        mockMvc.perform(post("/api/v1/teams/{teamId}/payment-requests/{id}/pay", OTHER_TEAM_ID, id))
                .andExpect(status().isForbidden());
    }
}
