package com.mannschaft.app.pointcard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.service.PointCardUserSettingsService;
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

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PointCardUserSettingsController} 契約テスト（F18 ウォレット ユーザー設定）。
 *
 * <p>認可根治戦役 Wave6 ロットG: {@code PointCardUserSettingsController#getSettings} /
 * {@code PointCardUserSettingsController#updateSettings} の自己スコープ性
 * （{@code SecurityUtils.getCurrentUserId()} のみが Service へ渡ること）を固定する。</p>
 *
 * <p>{@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} で
 * Controller のみを構成し Spring Security コンテキストを回避する（同型: {@code PaymentMethodControllerTest}）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardUserSettingsController 契約テスト")
class PointCardUserSettingsControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 99L;

    @Mock
    private PointCardUserSettingsService settingsService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        PointCardUserSettingsController controller = new PointCardUserSettingsController(settingsService);
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
    @DisplayName("PointCardUserSettingsController#getSettings: ログイン主体の userId のみが Service に渡る")
    void getSettings_passesAuthenticatedUserId() throws Exception {
        given(settingsService.getOrCreateSettings(eq(USER_ID)))
                .willReturn(new PointCardUserSettingsResponse(true, OffsetDateTime.now(), "1.0", false));

        mockMvc.perform(get("/api/v1/point-cards/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isEnabled").value(true));

        // 他ユーザー（OTHER_USER_ID）の設定は決して問い合わせない（自己スコープ性の固定）。
        org.mockito.Mockito.verify(settingsService).getOrCreateSettings(eq(USER_ID));
        org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
                .getOrCreateSettings(eq(OTHER_USER_ID));
    }

    @Test
    @DisplayName("PointCardUserSettingsController#updateSettings: ログイン主体の userId のみが Service に渡る")
    void updateSettings_passesAuthenticatedUserId() throws Exception {
        given(settingsService.updateSettings(eq(USER_ID), any()))
                .willReturn(new PointCardUserSettingsResponse(true, OffsetDateTime.now(), "1.0", true));

        mockMvc.perform(put("/api/v1/point-cards/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isEnabled\":true,\"requireBiometricOnShow\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requireBiometricOnShow").value(true));

        org.mockito.Mockito.verify(settingsService).updateSettings(eq(USER_ID), any());
        org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
                .updateSettings(eq(OTHER_USER_ID), any());
    }
}
