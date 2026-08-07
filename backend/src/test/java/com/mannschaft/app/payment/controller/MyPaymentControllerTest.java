package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentRequirementService;
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
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MyPaymentController} 契約テスト（F08.2 自分の支払い状況・未払い要件）。
 *
 * <p>認可根治戦役 Wave6 ロットG: {@code MyPaymentController#listMyPayments} /
 * {@code MyPaymentController#getPaymentRequirements} の自己スコープ性
 * （{@code SecurityUtils.getCurrentUserId()} のみが Service へ渡ること）を固定する。</p>
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} で
 * Controller のみを構成し Spring Security コンテキストを回避する（同型: {@code PaymentMethodControllerTest}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyPaymentController 契約テスト")
class MyPaymentControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    @Mock
    private MemberPaymentService memberPaymentService;

    @Mock
    private PaymentRequirementService paymentRequirementService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        MyPaymentController controller = new MyPaymentController(memberPaymentService, paymentRequirementService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("MyPaymentController#listMyPayments: ログイン主体の userId のみが Service に渡る")
    void listMyPayments_passesAuthenticatedUserId() throws Exception {
        given(memberPaymentService.listMyPayments(eq(USER_ID), any()))
                .willReturn(Page.empty());

        mockMvc.perform(get("/api/v1/me/payments"))
                .andExpect(status().isOk());

        Mockito.verify(memberPaymentService).listMyPayments(eq(USER_ID), any());
        Mockito.verify(memberPaymentService, Mockito.never()).listMyPayments(eq(OTHER_USER_ID), any());
    }

    @Test
    @DisplayName("MyPaymentController#getPaymentRequirements: ログイン主体の userId のみが Service に渡る")
    void getPaymentRequirements_passesAuthenticatedUserId() throws Exception {
        given(paymentRequirementService.getPaymentRequirements(eq(USER_ID)))
                .willReturn(List.of());

        mockMvc.perform(get("/api/v1/me/payment-requirements"))
                .andExpect(status().isOk());

        Mockito.verify(paymentRequirementService).getPaymentRequirements(eq(USER_ID));
        Mockito.verify(paymentRequirementService, Mockito.never()).getPaymentRequirements(eq(OTHER_USER_ID));
    }
}
