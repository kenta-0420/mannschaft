package com.mannschaft.app.billing.beta.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.BetaGrantQueryService;
import com.mannschaft.app.billing.beta.dto.BetaGrantItem;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20.3 ベータ特典 利用者向け照会 API 契約テスト（試練・test-first）。
 *
 * <p>AC-A5（/me は本人固定・scopeId を受けない）・AC-A7（利用者向けレスポンスに審査系フィールドが出ない）を検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F20.3 利用者向け ベータ特典 照会 API 契約テスト")
class BetaPerkControllerTest {

    @Mock
    private BetaGrantQueryService betaGrantQueryService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final long USER_ID = 9L;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        BetaPerkController controller = new BetaPerkController(betaGrantQueryService);
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
    @DisplayName("AC-A5: /me は currentUserId 固定で照会（scopeId をパスで受けない）")
    void getMyBetaPerks_usesCurrentUser() throws Exception {
        given(betaGrantQueryService.getMyBetaPerks(USER_ID))
                .willReturn(MyBetaPerksResponse.builder().grants(List.of()).eligibility(null).build());
        mockMvc.perform(get("/api/v1/me/beta-perks"))
                .andExpect(status().isOk());
        // 他人の userId ではなく必ず currentUserId で問い合わせる（IDOR 無効化）。
        verify(betaGrantQueryService).getMyBetaPerks(USER_ID);
    }

    @Test
    @DisplayName("AC-A7: チーム照会レスポンス（BetaGrantItem）に審査系フィールドが出ない")
    void getTeamBetaPerks_hidesReviewFields() throws Exception {
        BetaGrantItem item = BetaGrantItem.builder()
                .grantId("0198aaaa-bbbb-cccc-dddd-eeeeffff0002")
                .betaPhase(2)
                .grantKind("TEAM_ORG")
                .grantedAt(LocalDateTime.now())
                .featureKeys(List.of("ads.hide"))
                .activeMemberCountSnapshot(34)
                .build();
        given(betaGrantQueryService.getScopeBetaPerks(EntitlementScopeKind.TEAM, 123L))
                .willReturn(List.of(item));
        mockMvc.perform(get("/api/v1/teams/{teamId}/beta-perks", 123L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].featureKeys[0]").value("ads.hide"))
                .andExpect(jsonPath("$.data[0].reviewFlag").doesNotExist())
                .andExpect(jsonPath("$.data[0].reviewReason").doesNotExist())
                .andExpect(jsonPath("$.data[0].criteriaSnapshot").doesNotExist());
    }
}
