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

import java.time.Clock;
import java.time.Instant;
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

    private static final Instant RETRIED_AT = Instant.parse("2026-10-03T23:59:58Z");
    private static final Instant COMPLETED_AT = Instant.parse("2026-10-04T00:00:00Z");
    private static final LocalDateTime RETRIED_AT_SERVER = LocalDateTime.of(2026, 10, 4, 8, 59, 58);
    private static final LocalDateTime COMPLETED_AT_SERVER = LocalDateTime.of(2026, 10, 4, 9, 0);

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;
    @Mock
    private DashboardSettingsPurgeEventListener dashboardSettingsPurgeEventListener;
    @Mock
    private Clock clock;

    @InjectMocks
    private GdprSettingsPurgeRetryService service;

    // ---- テストヘルパー ----

    private AccountPurgeCompletionStatusEntity buildPendingEntity(Long userId, String domainName) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domainName);
        entity.setStatus("PENDING");
        entity.setAttemptedAt(RETRIED_AT_SERVER.minusHours(3));
        entity.setRetryCount(0);
        return entity;
    }

    private AccountPurgeCompletionStatusEntity buildSuccessEntity(Long userId, String domainName) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domainName);
        entity.setStatus("SUCCESS");
        entity.setAttemptedAt(RETRIED_AT_SERVER.minusHours(3));
        entity.setCompletedAt(RETRIED_AT_SERVER.minusHours(2));
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
        given(clock.instant()).willReturn(RETRIED_AT);
        doThrow(new org.springframework.transaction.TransactionSystemException("owner commit"))
                .when(dashboardSettingsPurgeEventListener).retryPurge(userId);

        var result = service.retryDomainPurge(userId, "dashboard");

        assertThat(result.succeeded()).isFalse();
        assertThat(entity.getStatus()).isEqualTo("PENDING");
        assertThat(entity.getRetryCount()).isEqualTo(1);
        assertThat(entity.getLastRetriedAt()).isEqualTo(RETRIED_AT_SERVER);
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
        given(clock.instant()).willReturn(RETRIED_AT, COMPLETED_AT);
        given(dashboardSettingsPurgeEventListener.retryPurge(userId)).willAnswer(invocation -> {
            assertThat(entity.getStatus()).isEqualTo("PENDING");
            assertThat(entity.getCompletedAt()).isNull();
            verify(clock, never()).instant();
            return true;
        });

        var result = service.retryDomainPurge(userId, "dashboard");

        assertThat(result.succeeded()).isTrue();
        assertThat(entity.getStatus()).isEqualTo("SUCCESS");
        assertThat(entity.getLastRetriedAt()).isEqualTo(RETRIED_AT_SERVER);
        assertThat(entity.getCompletedAt()).isEqualTo(COMPLETED_AT_SERVER);
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
        verify(clock, never()).instant();
        verify(completionStatusRepository, never()).save(entity);
    }
}
