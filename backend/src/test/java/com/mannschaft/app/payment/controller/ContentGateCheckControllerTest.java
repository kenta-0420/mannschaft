package com.mannschaft.app.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.service.ContentGateAccessService;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ContentGateCheckController} 契約テスト（F08.9 P4 ペイウォール判定 API）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>正常系: 200 OK / accessible・titleHidden・requiredItems が camelCase 1:1 で返る</li>
 *   <li>viewer=自分: Service へ {@code SecurityUtils.getCurrentUserId()} の値が渡る（IDOR 防止）</li>
 *   <li>titleHidden=true: requiredItems が空でも 200 で返る</li>
 *   <li>未認証: 401（COMMON_000）</li>
 * </ul>
 *
 * <h3>@WebMvcTest 非互換の回避</h3>
 * {@code @WebMvcTest + @EnableMethodSecurity} は SecurityConfig 全ロードを要求し失敗する（#1266 前科）。
 * 本テストは {@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} で
 * Controller のみを構成し Security コンテキストを回避する流儀を踏襲する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentGateCheckController 契約テスト（F08.9 P4）")
class ContentGateCheckControllerTest {

    private static final Long VIEWER_USER_ID = 42L;
    private static final String CONTENT_TYPE = "POST";
    private static final Long CONTENT_ID = 500L;

    @Mock
    private ContentGateAccessService contentGateAccessService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ContentGateCheckController controller = new ContentGateCheckController(contentGateAccessService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();

        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(VIEWER_USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("正常系: 200 OK / camelCase / viewer=自分が Service に渡る")
    void check_ok_200_camelCase() throws Exception {
        GateCheckResponse serviceResponse = new GateCheckResponse(
                false, false,
                List.of(new GateCheckResponse.RequiredItem(100L, "月会費", new BigDecimal("3000"), false)));
        given(contentGateAccessService.check(eq(CONTENT_TYPE), eq(CONTENT_ID), eq(VIEWER_USER_ID)))
                .willReturn(serviceResponse);

        mockMvc.perform(get("/api/v1/content-gates/check")
                        .param("contentType", CONTENT_TYPE)
                        .param("contentId", String.valueOf(CONTENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessible").value(false))
                .andExpect(jsonPath("$.data.titleHidden").value(false))
                .andExpect(jsonPath("$.data.requiredItems[0].paymentItemId").value(100))
                .andExpect(jsonPath("$.data.requiredItems[0].name").value("月会費"))
                .andExpect(jsonPath("$.data.requiredItems[0].faceAmount").value(3000))
                .andExpect(jsonPath("$.data.requiredItems[0].satisfied").value(false));
    }

    @Test
    @DisplayName("解錠済: accessible=true / requiredItems 空でも 200")
    void check_accessible_true() throws Exception {
        given(contentGateAccessService.check(eq(CONTENT_TYPE), eq(CONTENT_ID), eq(VIEWER_USER_ID)))
                .willReturn(new GateCheckResponse(true, false, List.of()));

        mockMvc.perform(get("/api/v1/content-gates/check")
                        .param("contentType", CONTENT_TYPE)
                        .param("contentId", String.valueOf(CONTENT_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessible").value(true))
                .andExpect(jsonPath("$.data.requiredItems").isEmpty());
    }

    @Test
    @DisplayName("titleHidden=true: requiredItems 空（存在秘匿）でも 200")
    void check_titleHidden_emptyItems() throws Exception {
        given(contentGateAccessService.check(eq(CONTENT_TYPE), eq(CONTENT_ID), eq(VIEWER_USER_ID)))
                .willThrow(new com.mannschaft.app.common.BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/content-gates/check")
                        .param("contentType", CONTENT_TYPE)
                        .param("contentId", String.valueOf(CONTENT_ID)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PAYMENT_015"));
    }

    @Test
    @DisplayName("未認証: 401（COMMON_000）")
    void check_unauthenticated_401() throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new com.mannschaft.app.common.BusinessException(CommonErrorCode.COMMON_000));

        mockMvc.perform(get("/api/v1/content-gates/check")
                        .param("contentType", CONTENT_TYPE)
                        .param("contentId", String.valueOf(CONTENT_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
    }

    @Test
    @DisplayName("contentId 欠落 → 400 Bad Request")
    void check_missingContentId_400() throws Exception {
        mockMvc.perform(get("/api/v1/content-gates/check")
                        .param("contentType", CONTENT_TYPE))
                .andExpect(status().isBadRequest());
    }
}
