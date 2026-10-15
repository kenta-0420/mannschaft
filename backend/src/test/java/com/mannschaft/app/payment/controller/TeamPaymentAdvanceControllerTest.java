package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.AdvanceSettlementStatus;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.service.TeamPaymentAdvanceService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TeamPaymentAdvanceController} 契約テスト（F08.9 P7 第二波）。
 *
 * <p>立替一覧 / 精算確認（SETTLED）/ IDOR 404（他チーム）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPaymentAdvanceController 契約テスト")
class TeamPaymentAdvanceControllerTest {

    private static final Long TEAM_ID = 600L;
    private static final Long OTHER_TEAM_ID = 999L;
    private static final Long ACTOR_ID = 700L;

    @Mock private TeamPaymentAdvanceService teamPaymentAdvanceService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        TeamPaymentAdvanceController controller = new TeamPaymentAdvanceController(teamPaymentAdvanceService);
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

    private TeamPaymentAdvanceEntity entity(AdvanceSettlementStatus status) {
        TeamPaymentAdvanceEntity a = TeamPaymentAdvanceEntity.builder()
                .organizationId(500L)
                .teamId(TEAM_ID)
                .payerUserId(ACTOR_ID)
                .paymentRequestId(UUID.randomUUID())
                .advancedAmount(30000)
                .currency("JPY")
                .settlementStatus(status)
                .build();
        a.setId(UUID.randomUUID());
        return a;
    }

    @Test
    @DisplayName("立替一覧: camelCase content")
    void 立替一覧() throws Exception {
        given(teamPaymentAdvanceService.findForTeam(TEAM_ID, ACTOR_ID))
                .willReturn(List.of(entity(AdvanceSettlementStatus.PENDING)));

        mockMvc.perform(get("/api/v1/teams/{teamId}/payment-advances", TEAM_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].settlementStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].advancedAmount").value(30000));
    }

    @Test
    @DisplayName("精算確認: SETTLED を返す")
    void 精算確認() throws Exception {
        UUID id = UUID.randomUUID();
        given(teamPaymentAdvanceService.confirmSettlement(TEAM_ID, id, ACTOR_ID))
                .willReturn(entity(AdvanceSettlementStatus.SETTLED));

        mockMvc.perform(post("/api/v1/teams/{teamId}/payment-advances/{id}/confirm-settlement", TEAM_ID, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlementStatus").value("SETTLED"));
    }

    @Test
    @DisplayName("IDOR: 他チーム宛て精算確認は 404（ADVANCE_NOT_FOUND）")
    void 他チーム404() throws Exception {
        UUID id = UUID.randomUUID();
        given(teamPaymentAdvanceService.confirmSettlement(eq(OTHER_TEAM_ID), eq(id), any()))
                .willThrow(new BusinessException(MembershipBillingErrorCode.PAYMENT_ADVANCE_NOT_FOUND));

        mockMvc.perform(post("/api/v1/teams/{teamId}/payment-advances/{id}/confirm-settlement", OTHER_TEAM_ID, id))
                .andExpect(status().isNotFound());
    }
}
