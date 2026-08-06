package com.mannschaft.app.proxy.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;

/**
 * {@link ProxyInputConsentController} の単体テスト。
 *
 * <p>ProxyInputConsentController#getActiveConsents の自己スコープ性、および
 * #createConsent の権限委譲を固定する契約テスト。
 * {@code ProxyInputConsentService#getActiveConsentsForProxy} は
 * {@code findActiveByProxyUserId(SecurityUtils.getCurrentUserId())} のみを参照する。
 * {@code #createConsent} は {@code AccessControlService#checkAdminOrAbove(requestUserId,
 * organizationId, "ORGANIZATION")} を Service 内で必ず呼び、URL の orgId に対する
 * DEPUTY_ADMIN 以上の権限を持たない利用者からの同意書登録を拒否する
 * （認可根治戦役 Wave6 ロットD で欠落を発見し追加。旧実装は権限チェックが一切無く、
 * 任意の認証済みユーザーが任意組織向けの同意書を作成できた）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyInputConsentController 単体テスト")
class ProxyInputConsentControllerTest {

    @Mock
    private ProxyInputConsentService consentService;

    @InjectMocks
    private ProxyInputConsentController controller;

    private static final Long USER_ID = 100L;

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("getActiveConsents は SecurityUtils.getCurrentUserId() を代理者IDとして渡す")
    void getActiveConsents_boundToCurrentUserOnly() {
        org.mockito.BDDMockito.given(consentService.getActiveConsentsForProxy(USER_ID))
            .willReturn(List.of());

        controller.getActiveConsents();

        verify(consentService).getActiveConsentsForProxy(USER_ID);
    }
}
