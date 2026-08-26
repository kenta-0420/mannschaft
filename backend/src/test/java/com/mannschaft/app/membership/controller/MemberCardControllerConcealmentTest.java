package com.mannschaft.app.membership.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MemberCardController} の存在秘匿契約テスト（認可監査 Wave6 ロットC）。
 *
 * <p>{@code MemberCardService#getQrToken} は cardId 直指定で他人の会員証にアクセスした場合、
 * 会員証不在（MEMBERSHIP_001）と同一の {@code MEMBERSHIP_002} を送出する
 * （{@code MemberCardService.java} 118-119行 実測）。ERROR_CODE_STATUS_MAP に未登録のままだと
 * Severity.WARN 既定の 400 が返り、「その cardId は存在するが他人のもの」と実在を漏らしてしまう。
 * 本テストは 404 への登録が実 HTTP 経路で機能することを固定する。</p>
 *
 * <p>{@code @WebMvcTest} は {@code @Component} 実装の Filter（{@code JwtAuthenticationFilter}・
 * {@code ProxyInputContextFilter} 等）をパッケージ非依存で自動検出し Bean 化するため、
 * {@code addFilters=false} でフィルタ適用を止めても Bean 構築自体は必要になる
 * （{@code VillageReportControllerTest}（village パッケージ）と同型のモック集合を揃える。
 * 揃えないと ApplicationContext 起動失敗でクラス内の全テストが red になる）。</p>
 */
@WebMvcTest(MemberCardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("MemberCardController 存在秘匿契約テスト（MEMBERSHIP_002 → 404）")
class MemberCardControllerConcealmentTest {

    private static final Long USER_ID = 100L;
    private static final Long OTHERS_CARD_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberCardService memberCardService;

    /** JwtAuthenticationFilter（@Component・自動検出）の依存解決。 */
    @MockitoBean
    private AuthTokenService authTokenService;

    /** ProxyInputContextFilter（@Component・自動検出）の依存解決。 */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** @EnableMethodSecurity 有効化後の SpEL ガード依存解決（VillageReportControllerTest と同型）。 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("存在秘匿 red→green: 他人の会員証 QR トークン取得 → 404（MEMBERSHIP_002 存在秘匿マップ）")
    void getQrToken_othersCard_notFound() throws Exception {
        willThrow(new BusinessException(MembershipErrorCode.MEMBERSHIP_002))
                .given(memberCardService).getQrToken(eq(OTHERS_CARD_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/member-cards/" + OTHERS_CARD_ID + "/qr"))
                .andExpect(status().isNotFound());
    }
}
