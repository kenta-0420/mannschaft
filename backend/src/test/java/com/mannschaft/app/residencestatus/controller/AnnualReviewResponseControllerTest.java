package com.mannschaft.app.residencestatus.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.AnnualReviewResponseDto;
import com.mannschaft.app.residencestatus.dto.SubmitAnnualResponseRequest;
import com.mannschaft.app.residencestatus.service.AnnualReviewResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AnnualReviewResponseController} の MockMvc 結合テスト（F09.16 S3-A・認可監査 Wave6 後続）。
 *
 * <p>認可根治戦役で {@code RESIDENCE_STATUS_004}（ANNUAL_REVIEW_RESPONSE_NOT_FOUND）を
 * 400 → 404 に是正した（他居住者の residentRegistryId を指定した越境を「不在」として存在秘匿する）。
 * これまで {@code GlobalExceptionHandlerTest} の写像アサーションのみでカバーされており、
 * エンドポイントを実際に叩いて越境時に 404 が返ることを固定する契約 IT が無かった。</p>
 *
 * <p>本テストは実 HTTP 経路（Controller → Service（mock）→ GlobalExceptionHandler）で:</p>
 * <ul>
 *   <li>陽性対照: 正当な利用者が自分の対象へ回答を送信すると成功する（200）</li>
 *   <li>越境: 他居住者の residentRegistryId を指定すると 404 が返る（403/400 ではない）</li>
 * </ul>
 * ことを固定する。
 */
@WebMvcTest(AnnualReviewResponseController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AnnualReviewResponseController 結合テスト（越境アクセスの存在秘匿）")
class AnnualReviewResponseControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long ORG_ID = 1L;
    private static final UUID REVIEW_ID = UUID.fromString("01956c00-0000-7000-8000-000000000001");
    private static final UUID RESPONSE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000aaa");
    private static final Long DWELLING_UNIT_ID = 50L;
    private static final Long OWN_RESIDENT_REGISTRY_ID = 200L;
    private static final Long OTHER_RESIDENT_REGISTRY_ID = 999L;

    private static final String SUBMIT_PATH =
            "/api/v1/organizations/" + ORG_ID + "/residence-status/annual-reviews/" + REVIEW_ID + "/responses/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AnnualReviewResponseService responseService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private SubmitAnnualResponseRequest requestBody(Long residentRegistryId) {
        return SubmitAnnualResponseRequest.builder()
                .dwellingUnitId(DWELLING_UNIT_ID)
                .residentRegistryId(residentRegistryId)
                .residenceState("OWNER_RESIDING")
                .contactPhoneVerified(true)
                .build();
    }

    // ------------------------------------------------------------------
    // PUT /responses/me
    // ------------------------------------------------------------------

    @Test
    @DisplayName("陽性対照: 自分の居住者台帳へ回答送信すると 200 で回答が返る")
    void submitMyResponse_ownResidentRegistry_200() throws Exception {
        AnnualReviewResponseDto dto = AnnualReviewResponseDto.builder()
                .id(RESPONSE_ID)
                .annualReviewId(REVIEW_ID)
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_UNIT_ID)
                .residentRegistryId(OWN_RESIDENT_REGISTRY_ID)
                .respondentUserId(USER_ID)
                .residenceState("OWNER_RESIDING")
                .contactPhoneVerified(true)
                .respondedAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .createdAt(LocalDateTime.of(2026, 5, 14, 10, 0))
                .build();
        given(responseService.submitResponse(eq(ORG_ID), eq(REVIEW_ID), eq(USER_ID),
                any(SubmitAnnualResponseRequest.class))).willReturn(dto);

        String body = objectMapper.writeValueAsString(requestBody(OWN_RESIDENT_REGISTRY_ID));

        mockMvc.perform(put(SUBMIT_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(RESPONSE_ID.toString()))
                .andExpect(jsonPath("$.data.residentRegistryId").value(OWN_RESIDENT_REGISTRY_ID))
                .andExpect(jsonPath("$.data.residenceState").value("OWNER_RESIDING"));
    }

    @Test
    @DisplayName("越境: 他居住者の residentRegistryId を指定すると 404（存在秘匿・RESIDENCE_STATUS_004）")
    void submitMyResponse_otherResidentRegistry_404() throws Exception {
        willThrow(new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_RESPONSE_NOT_FOUND))
                .given(responseService).submitResponse(eq(ORG_ID), eq(REVIEW_ID), eq(USER_ID),
                        any(SubmitAnnualResponseRequest.class));

        String body = objectMapper.writeValueAsString(requestBody(OTHER_RESIDENT_REGISTRY_ID));

        mockMvc.perform(put(SUBMIT_PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESIDENCE_STATUS_004"));
    }
}
