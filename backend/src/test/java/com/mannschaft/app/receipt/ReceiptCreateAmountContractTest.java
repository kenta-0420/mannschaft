package com.mannschaft.app.receipt;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.receipt.controller.ReceiptAdminController;
import com.mannschaft.app.receipt.dto.CreateReceiptRequest;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import com.mannschaft.app.receipt.service.ReceiptExportService;
import com.mannschaft.app.receipt.service.ReceiptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 領収書発行リクエストの金額契約テスト（CMP-260907-0915 の根治）。
 *
 * <h2>守るバグ</h2>
 * <p>FE は金額を {@code totalAmount} という名前で送っていたが、BE
 * {@link CreateReceiptRequest} が受けるのは {@code amount} である。
 * さらに {@code amount} に {@code @NotNull} が無かったため、名前違いのリクエストは
 * <b>入口で 400 にならず</b>、{@code ReceiptService#createReceipt} が
 * {@code amount.subtract(...)} で NPE を起こすところまで進んでいた
 * （金額の無い領収書という、領収書として成立しない状態を許す入口だった）。</p>
 *
 * <h2>方針</h2>
 * <p>{@code ReceiptScopeTypeContractTest} と同型の
 * {@code MockMvcBuilders.standaloneSetup} ＋ {@link GlobalExceptionHandler}。
 * standaloneSetup は jakarta validation をクラスパスから拾って
 * {@code @Valid} を実際に効かせるため、DTO のアノテーション有無をそのまま測れる。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("領収書発行の金額契約（CMP-260907-0915）")
class ReceiptCreateAmountContractTest {

    private static final long USER_ID = 920810001L;

    @Mock private ReceiptService receiptService;
    @Mock private ReceiptExportService exportService;

    private MockMvc mvc;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        // Spring Boot の既定に合わせて未知プロパティを無視する。
        // これを外すと totalAmount が「未知フィールド」として 400 になり、
        // 測りたい @NotNull ではなく別の理由で赤くなってしまう。
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        MessageSource messageSource = new StaticMessageSource();
        mvc = MockMvcBuilders.standaloneSetup(new ReceiptAdminController(receiptService, exportService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(messageSource))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("金額を送らない発行要求は 400（COMMON_001）— サービスへ到達しない")
    void createReceipt_withoutAmount_badRequest() throws Exception {
        mvc.perform(post("/api/v1/admin/receipts")
                        .param("scopeType", "TEAM")
                        .param("scopeId", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"山田 太郎\",\"description\":\"参加費\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("旧 FE の名前違い totalAmount は金額として認められず 400 — 金額 null のまま作らせない")
    void createReceipt_withLegacyTotalAmountFieldName_badRequest() throws Exception {
        mvc.perform(post("/api/v1/admin/receipts")
                        .param("scopeType", "TEAM")
                        .param("scopeId", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"山田 太郎\",\"totalAmount\":10000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }

    @Test
    @DisplayName("amount で送れば通り、サービスにはその金額がそのまま渡る")
    void createReceipt_withAmount_passesAmountThrough() throws Exception {
        when(receiptService.createReceipt(any(), any(), any(), any()))
                .thenReturn(ReceiptResponse.builder().id(1L).amount(new BigDecimal("10000")).build());

        mvc.perform(post("/api/v1/admin/receipts")
                        .param("scopeType", "TEAM")
                        .param("scopeId", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"山田 太郎\",\"amount\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.amount").value(10000));

        ArgumentCaptor<CreateReceiptRequest> captor = ArgumentCaptor.forClass(CreateReceiptRequest.class);
        verify(receiptService).createReceipt(eq(ReceiptScopeType.TEAM), eq(12L), eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("プレビューも同じ DTO を使うため、金額なしは 400 で弾かれる")
    void previewReceipt_withoutAmount_badRequest() throws Exception {
        mvc.perform(post("/api/v1/admin/receipts/preview")
                        .param("scopeType", "TEAM")
                        .param("scopeId", "12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"山田 太郎\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
        verifyNoInteractions(receiptService);
    }
}
