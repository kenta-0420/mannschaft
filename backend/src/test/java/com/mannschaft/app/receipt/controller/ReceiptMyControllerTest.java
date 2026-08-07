package com.mannschaft.app.receipt.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.receipt.dto.AnnualSummaryResponse;
import com.mannschaft.app.receipt.service.ReceiptMyService;
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

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ReceiptMyController} 契約テスト（F08.4 自分宛の領収書取得）。
 *
 * <p>認可根治戦役 Wave6 ロットG: {@code ReceiptMyController#listMyReceipts} /
 * {@code ReceiptMyController#getAnnualSummary} の自己スコープ性
 * （{@code SecurityUtils.getCurrentUserId()} のみが recipientUserId として Service へ渡ること）を固定する。</p>
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} で
 * Controller のみを構成し Spring Security コンテキストを回避する（同型: {@code PaymentMethodControllerTest}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReceiptMyController 契約テスト")
class ReceiptMyControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    @Mock
    private ReceiptMyService receiptMyService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ReceiptMyController controller = new ReceiptMyController(receiptMyService);
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
    @DisplayName("ReceiptMyController#listMyReceipts: ログイン主体の userId のみが recipientUserId として Service に渡る")
    void listMyReceipts_passesAuthenticatedUserId() throws Exception {
        given(receiptMyService.listMyReceipts(eq(USER_ID), isNull(), isNull(), eq(0), eq(20)))
                .willReturn(PagedResponse.of(List.of(), new PagedResponse.PageMeta(0, 0, 20, 0)));

        mockMvc.perform(get("/api/v1/my/receipts"))
                .andExpect(status().isOk());

        Mockito.verify(receiptMyService).listMyReceipts(eq(USER_ID), isNull(), isNull(), eq(0), eq(20));
        Mockito.verify(receiptMyService, Mockito.never())
                .listMyReceipts(eq(OTHER_USER_ID), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("ReceiptMyController#getAnnualSummary: ログイン主体の userId のみが recipientUserId として Service に渡る")
    void getAnnualSummary_passesAuthenticatedUserId() throws Exception {
        given(receiptMyService.getAnnualSummary(eq(USER_ID), eq(2026), isNull(), isNull()))
                .willReturn(AnnualSummaryResponse.builder()
                        .year(2026)
                        .totalAmount(BigDecimal.ZERO)
                        .totalCount(0)
                        .totalTaxAmount(BigDecimal.ZERO)
                        .voidedCount(0)
                        .voidedAmount(BigDecimal.ZERO)
                        .build());

        mockMvc.perform(get("/api/v1/my/receipts/annual-summary").param("year", "2026"))
                .andExpect(status().isOk());

        Mockito.verify(receiptMyService).getAnnualSummary(eq(USER_ID), eq(2026), isNull(), isNull());
        Mockito.verify(receiptMyService, Mockito.never())
                .getAnnualSummary(eq(OTHER_USER_ID), any(Integer.class), any(), any());
    }
}
