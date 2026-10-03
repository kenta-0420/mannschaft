package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.dashboard.event.DashboardSettingsPurgeEventListener;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GdprSettingsPurgeRetryService} 単体テスト（Mockito）。
 *
 * <p>Phase F GDPR パージ手動 retry サービスのロジックを検証する。
 * リポジトリ・リスナーはすべて Mock で差し替え、純粋なビジネスロジックのみを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GdprSettingsPurgeRetryService 単体テスト")
class GdprSettingsPurgeRetryServiceTest {

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;
    @Mock
    private DashboardSettingsPurgeEventListener dashboardSettingsPurgeEventListener;

    @InjectMocks
    private GdprSettingsPurgeRetryService service;

    // ---- テストヘルパー ----

    private AccountPurgeCompletionStatusEntity buildPendingEntity(Long userId, String domainName) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domainName);
        entity.setStatus("PENDING");
        entity.setAttemptedAt(LocalDateTime.now().minusHours(3));
        entity.setRetryCount(0);
        return entity;
    }

    private AccountPurgeCompletionStatusEntity buildSuccessEntity(Long userId, String domainName) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domainName);
        entity.setStatus("SUCCESS");
        entity.setAttemptedAt(LocalDateTime.now().minusHours(3));
        entity.setCompletedAt(LocalDateTime.now().minusHours(2));
        entity.setRetryCount(1);
        return entity;
    }

    @Test
    @DisplayName("設定owner proxyの完了失敗もPENDINGのまま試行回数へ記録する")
    void settingsOwnerCommitFailureRemainsPending() {
        Long userId = 909L;
        var entity = buildPendingEntity(userId, "dashboard");
        given(completionStatusRepository.findByUserIdAndDomainName(userId, "dashboard"))
                .willReturn(Optional.of(entity));
        doThrow(new org.springframework.transaction.TransactionSystemException("owner commit"))
                .when(dashboardSettingsPurgeEventListener).retryPurge(userId);

        var result = service.retryDomainPurge(userId, "dashboard");

        assertThat(result.succeeded()).isFalse();
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getRetryCount()).isEqualTo(1);
        assertThat(entity.getLastRetriedAt()).isNotNull();
        assertThat(entity.getCompletedAt()).isNull();
        verify(completionStatusRepository).save(entity);
    }

    @Test
    @DisplayName("設定owner proxyがcommit成立して返った後だけSUCCESSへ進む")
    void settingsSuccessFollowsOwnerProxyReturn() {
        Long userId = 910L;
        var entity = buildPendingEntity(userId, "dashboard");
        given(completionStatusRepository.findByUserIdAndDomainName(userId, "dashboard"))
                .willReturn(Optional.of(entity));
        given(dashboardSettingsPurgeEventListener.retryPurge(userId)).willAnswer(invocation -> {
            assertThat(entity.getStatus()).isEqualTo("PENDING");
            assertThat(entity.getCompletedAt()).isNull();
            return true;
        });

        var result = service.retryDomainPurge(userId, "dashboard");

        assertThat(result.succeeded()).isTrue();
        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getCompletedAt()).isNotNull();
        verify(completionStatusRepository).save(entity);
    }

    @Test
    @DisplayName("既SUCCESSの設定domainではownerへ再度入らず試行数も維持する")
    void settingsAlreadySuccessfulDoesNotRetry() {
        Long userId = 911L;
        var entity = buildSuccessEntity(userId, "dashboard");
        given(completionStatusRepository.findByUserIdAndDomainName(userId, "dashboard"))
                .willReturn(Optional.of(entity));

        var result = service.retryDomainPurge(userId, "dashboard");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.retryCount()).isEqualTo(1);
        verify(dashboardSettingsPurgeEventListener, never()).retryPurge(userId);
        verify(completionStatusRepository, never()).save(entity);
    }
}
