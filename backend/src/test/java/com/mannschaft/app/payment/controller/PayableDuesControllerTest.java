package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.BulkCheckoutRequest;
import com.mannschaft.app.payment.dto.BulkCheckoutResponse;
import com.mannschaft.app.payment.dto.BulkCheckoutResultItem;
import com.mannschaft.app.payment.dto.PayableDueItem;
import com.mannschaft.app.payment.dto.PayableDuesResponse;
import com.mannschaft.app.payment.service.PayableDuesService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PayableDuesController} 契約テスト（F08.9 P2 後見まとめ払い）。
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@code @ExtendWith(MockitoExtension.class)} で Controller・
 * AdviceLayer のみ構成し Spring Security を回避する（{@code @WebMvcTest + @EnableMethodSecurity} 非互換回避・
 * {@link PaymentCheckoutControllerTest} と同型）。{@code SecurityUtils.getCurrentUserId()} は MockedStatic で差し替える。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PayableDuesController 契約テスト")
class PayableDuesControllerTest {

    private static final Long PAYER_USER_ID = 1L;

    @Mock
    private PayableDuesService payableDuesService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        PayableDuesController controller = new PayableDuesController(payableDuesService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(PAYER_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("GET payable-dues 正常系 → 200・items が camelCase で返る")
    void getPayableDues_200() throws Exception {
        PayableDueItem item = new PayableDueItem(
                200L, "子 ユーザー", "TEAM", 1L, "テストチーム",
                10L, "年会費", 5000, 0, 5000, null, "TERM", "GUARDIAN",
                false, null, null, null);
        given(payableDuesService.getPayableDues(PAYER_USER_ID))
                .willReturn(new PayableDuesResponse(List.of(item)));

        mockMvc.perform(get("/api/v1/me/payable-dues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].beneficiaryUserId").value(200))
                .andExpect(jsonPath("$.data.items[0].authorizationVia").value("GUARDIAN"))
                .andExpect(jsonPath("$.data.items[0].totalCharge").value(5000));
    }

    @Test
    @DisplayName("POST bulk-checkout 正常系 → 200・部分成功の結果が返る")
    void bulkCheckout_200() throws Exception {
        BulkCheckoutResponse response = new BulkCheckoutResponse(List.of(
                BulkCheckoutResultItem.checkedOut(10L),
                BulkCheckoutResultItem.skipped(11L, "ALREADY_PAID")));
        given(payableDuesService.bulkCheckout(anyLong(), any(BulkCheckoutRequest.class)))
                .willReturn(response);

        String body = objectMapper.writeValueAsString(
                new BulkCheckoutRequest(200L, List.of(10L, 11L)));

        mockMvc.perform(post("/api/v1/me/payable-dues/bulk-checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.results[0].status").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.data.results[1].status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.results[1].skipReason").value("ALREADY_PAID"));
    }

    @Test
    @DisplayName("POST bulk-checkout バリデーション: paymentItemIds 空 → 400")
    void bulkCheckout_emptyItems_400() throws Exception {
        String body = "{\"beneficiaryUserId\":200,\"paymentItemIds\":[]}";

        mockMvc.perform(post("/api/v1/me/payable-dues/bulk-checkout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET payable-dues 未認証 → 401（COMMON_000）")
    void getPayableDues_unauthenticated_401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/me/payable-dues"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }
}
