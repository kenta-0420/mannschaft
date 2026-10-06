package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("GDPR手動retryのTX境界ルーティング")
class GdprPurgeRetryFacadeTest {

    @Mock private GdprPurgeRetryService legacyRetryService;
    @Mock private GdprSettingsPurgeRetryService settingsRetryService;
    @InjectMocks private GdprPurgeRetryFacade facade;

    @Test
    @DisplayName("旧ドメインは既存TXサービスの結果をそのまま返す")
    void legacyDomainKeepsLegacyEntry() {
        var expected = new RetryResultResponse(true, "role", "SUCCESS", 1, "retry 成功");
        given(legacyRetryService.retryDomainPurge(100L, "role")).willReturn(expected);

        assertThat(facade.retryDomainPurge(100L, "role")).isSameAs(expected);

        verify(legacyRetryService).retryDomainPurge(100L, "role");
        verify(settingsRetryService, never()).retryDomainPurge(100L, "role");
    }

    @Test
    @DisplayName("設定ドメインは旧TXサービスへ入らず専用非TX入口へ渡す")
    void settingsDomainAvoidsLegacyTransaction() {
        var expected = new RetryResultResponse(false, "dashboard", "PENDING", 2, "retry 失敗（PENDING 継続）");
        given(settingsRetryService.supports("dashboard")).willReturn(true);
        given(settingsRetryService.retryDomainPurge(101L, "dashboard")).willReturn(expected);

        assertThat(facade.retryDomainPurge(101L, "dashboard")).isSameAs(expected);

        verify(settingsRetryService).retryDomainPurge(101L, "dashboard");
        verifyNoInteractions(legacyRetryService);
    }

    @Test
    @DisplayName("不明ドメインの既存例外は改変せず呼び出し元へ返す")
    void unknownDomainKeepsExistingValidation() {
        given(legacyRetryService.retryDomainPurge(102L, "unknown"))
                .willThrow(new IllegalArgumentException("不明なドメイン名: unknown"));

        assertThatThrownBy(() -> facade.retryDomainPurge(102L, "unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不明なドメイン名: unknown");

        verify(settingsRetryService, never()).retryDomainPurge(102L, "unknown");
    }
}
