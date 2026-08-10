package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MyPaymentController} 契約テスト（F08.2 自分の支払い状況・未払い要件）。
 *
 * <p>本テストは {@link com.mannschaft.app.common.security.SelfScopedEndpoint} を付与した
 * MyPaymentController#listMyPayments・MyPaymentController#getPaymentRequirements・
 * MyPaymentController#listMySubscriptions の自己スコープ性を固定する
 * （認可漏れ(IDOR)全域監査戦役 第7波ロットA）。3 メソッドとも
 * リクエストで他ユーザーの識別子を受け取らず、{@code SecurityUtils.getCurrentUserId()} の値のみが
 * Service へ渡ることを実測する。</p>
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@code @ExtendWith(MockitoExtension.class)} で
 * Controller・AdviceLayer のみ構成し Spring Security を回避する
 * （{@code @WebMvcTest + @EnableMethodSecurity} 非互換回避・{@link PayableDuesControllerTest} と同型）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyPaymentController 契約テスト（第7波ロットA・自己スコープ）")
class MyPaymentControllerTest {

    private static final Long CALLER_USER_ID = 7L;

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
        MyPaymentController controller =
                new MyPaymentController(memberPaymentService, paymentRequirementService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(CALLER_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("listMyPayments: Service には SecurityUtils の userId のみが渡る（自己スコープ）")
    void listMyPayments_自己スコープでService呼び出し() throws Exception {
        Page<MemberPaymentResponse> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        given(memberPaymentService.listMyPayments(eq(CALLER_USER_ID), any(PageRequest.class)))
                .willReturn(page);

        mockMvc.perform(get("/api/v1/me/payments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("listMyPayments: 未認証は401（COMMON_000）")
    void listMyPayments_未認証は401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/me/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }

    @Test
    @DisplayName("getPaymentRequirements: Service には SecurityUtils の userId のみが渡る（自己スコープ）")
    void getPaymentRequirements_自己スコープでService呼び出し() throws Exception {
        given(paymentRequirementService.getPaymentRequirements(eq(CALLER_USER_ID)))
                .willReturn(List.<PaymentRequirementResponse>of());

        mockMvc.perform(get("/api/v1/me/payment-requirements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("getPaymentRequirements: 未認証は401（COMMON_000）")
    void getPaymentRequirements_未認証は401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/me/payment-requirements"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }

    @Test
    @DisplayName("listMySubscriptions: 200・常に空配列（Phase 4 未実装のため対象データへの到達経路が無い）")
    void listMySubscriptions_常に空配列() throws Exception {
        // Phase 4 未実装のため Controller は SecurityUtils を呼ばず固定の空配列を返す。
        // 未認証遮断は SecurityConfig の anyRequest().authenticated()（deny-by-default）が担保し、
        // standalone 構成の本テストではフィルタチェーンを経由しないため対象外とする。
        mockMvc.perform(get("/api/v1/me/subscriptions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
