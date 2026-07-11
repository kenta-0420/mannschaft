package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.billing.BillingPurgeEventListener;
import com.mannschaft.app.chart.event.ChartPurgeEventListener;
import com.mannschaft.app.errorreport.event.ErrorReportPurgeEventListener;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.payment.event.PaymentPurgeEventListener;
import com.mannschaft.app.proxy.event.ProxyPurgeEventListener;
import com.mannschaft.app.role.event.RolePurgeEventListener;
import com.mannschaft.app.team.event.TeamPurgeEventListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GdprPurgeRetryService} 単体テスト（Mockito）。
 *
 * <p>Phase F GDPR パージ手動 retry サービスのロジックを検証する。
 * リポジトリ・リスナーはすべて Mock で差し替え、純粋なビジネスロジックのみを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GdprPurgeRetryService 単体テスト")
class GdprPurgeRetryServiceTest {

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;
    @Mock
    private RolePurgeEventListener rolePurgeEventListener;
    @Mock
    private TeamPurgeEventListener teamPurgeEventListener;
    @Mock
    private PaymentPurgeEventListener paymentPurgeEventListener;
    @Mock
    private ChartPurgeEventListener chartPurgeEventListener;
    @Mock
    private ProxyPurgeEventListener proxyPurgeEventListener;
    @Mock
    private ErrorReportPurgeEventListener errorReportPurgeEventListener;
    @Mock
    private BillingPurgeEventListener billingPurgeEventListener;

    @InjectMocks
    private GdprPurgeRetryService service;

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

    // ---- 正常系 ----

    @Nested
    @DisplayName("正常系: PENDING → retry 成功 → SUCCESS に遷移")
    class RetrySuccessTest {

        @Test
        @DisplayName("role ドメインの retry が成功した場合、status=SUCCESS、retryCount が increment される")
        void role_retry成功_SUCCESSに遷移() {
            Long userId = 100L;
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, "role");
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "role"))
                    .willReturn(Optional.of(entity));
            given(rolePurgeEventListener.retryPurge(userId)).willReturn(true);

            RetryResultResponse result = service.retryDomainPurge(userId, "role");

            assertThat(result.succeeded()).isTrue();
            assertThat(result.newStatus()).isEqualTo("SUCCESS");
            assertThat(result.retryCount()).isEqualTo(1);
            assertThat(result.domainName()).isEqualTo("role");

            // エンティティが SUCCESS に更新されて save されたか確認
            ArgumentCaptor<AccountPurgeCompletionStatusEntity> captor =
                    ArgumentCaptor.forClass(AccountPurgeCompletionStatusEntity.class);
            verify(completionStatusRepository).save(captor.capture());
            AccountPurgeCompletionStatusEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo("SUCCESS");
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getLastRetriedAt()).isNotNull();
            assertThat(saved.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("team ドメインの retry が成功した場合、retryCount が increment される")
        void team_retry成功_retryCount増加() {
            Long userId = 200L;
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, "team");
            entity.setRetryCount(2); // 既に 2 回 retry 済み
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "team"))
                    .willReturn(Optional.of(entity));
            given(teamPurgeEventListener.retryPurge(userId)).willReturn(true);

            RetryResultResponse result = service.retryDomainPurge(userId, "team");

            assertThat(result.retryCount()).isEqualTo(3); // 2 + 1
            assertThat(result.succeeded()).isTrue();
        }

        @Test
        @DisplayName("retryCount が累積されること（2 回目の retry で retryCount=2）")
        void retryCount累積確認() {
            Long userId = 300L;
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, "payment");
            entity.setRetryCount(1); // 1 回目の retry 後
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "payment"))
                    .willReturn(Optional.of(entity));
            given(paymentPurgeEventListener.retryPurge(userId)).willReturn(true);

            RetryResultResponse result = service.retryDomainPurge(userId, "payment");

            assertThat(result.retryCount()).isEqualTo(2); // 1 + 1
        }
    }

    @Nested
    @DisplayName("正常系: 既に SUCCESS → 即座に返す（retry 未実行）")
    class AlreadySuccessTest {

        @Test
        @DisplayName("status=SUCCESS の場合、リスナー retry は呼ばれず即座に返す")
        void SUCCESS_リスナー未呼び出し_即時返却() {
            Long userId = 400L;
            AccountPurgeCompletionStatusEntity entity = buildSuccessEntity(userId, "chart");
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "chart"))
                    .willReturn(Optional.of(entity));

            RetryResultResponse result = service.retryDomainPurge(userId, "chart");

            assertThat(result.succeeded()).isTrue();
            assertThat(result.newStatus()).isEqualTo("SUCCESS");
            assertThat(result.message()).isEqualTo("既に処理済みです");

            // リスナーは呼ばれない
            verify(chartPurgeEventListener, never()).retryPurge(any());
            // save も呼ばれない
            verify(completionStatusRepository, never()).save(any());
        }
    }

    // ---- 異常系 ----

    @Nested
    @DisplayName("異常系: retry 失敗 → PENDING 継続、retry_count は increment される")
    class RetryFailedTest {

        @Test
        @DisplayName("proxy ドメインの retry が失敗した場合、status=PENDING のまま retryCount は increment される")
        void proxy_retry失敗_PENDING継続_retryCount増加() {
            Long userId = 500L;
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, "proxy");
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "proxy"))
                    .willReturn(Optional.of(entity));
            given(proxyPurgeEventListener.retryPurge(userId)).willReturn(false);

            RetryResultResponse result = service.retryDomainPurge(userId, "proxy");

            assertThat(result.succeeded()).isFalse();
            assertThat(result.newStatus()).isEqualTo("PENDING");
            assertThat(result.retryCount()).isEqualTo(1); // 失敗しても increment

            ArgumentCaptor<AccountPurgeCompletionStatusEntity> captor =
                    ArgumentCaptor.forClass(AccountPurgeCompletionStatusEntity.class);
            verify(completionStatusRepository).save(captor.capture());
            AccountPurgeCompletionStatusEntity saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo("PENDING"); // PENDING のまま
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getLastRetriedAt()).isNotNull();
            assertThat(saved.getCompletedAt()).isNull(); // completedAt は更新されない
        }

        @Test
        @DisplayName("errorreport ドメインの retry が失敗した場合のメッセージ確認")
        void errorreport_retry失敗_メッセージ確認() {
            Long userId = 600L;
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, "errorreport");
            given(completionStatusRepository.findByUserIdAndDomainName(userId, "errorreport"))
                    .willReturn(Optional.of(entity));
            given(errorReportPurgeEventListener.retryPurge(userId)).willReturn(false);

            RetryResultResponse result = service.retryDomainPurge(userId, "errorreport");

            assertThat(result.message()).isEqualTo("retry 失敗（PENDING 継続）");
        }
    }

    @Nested
    @DisplayName("異常系: 存在しない userId/domain → IllegalArgumentException")
    class NotFoundTest {

        @Test
        @DisplayName("対象レコードが存在しない場合、IllegalArgumentException が投げられる")
        void 対象レコードなし_例外() {
            given(completionStatusRepository.findByUserIdAndDomainName(999L, "role"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.retryDomainPurge(999L, "role"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("対象レコードが見つかりません");
        }

        @Test
        @DisplayName("不明なドメイン名の場合、IllegalArgumentException が投げられる")
        void 不明なドメイン名_例外() {
            assertThatThrownBy(() -> service.retryDomainPurge(100L, "unknown"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不明なドメイン名");

            // リポジトリすら呼ばれない
            verify(completionStatusRepository, never()).findByUserIdAndDomainName(any(), any());
        }
    }

    // ---- 各ドメインの正常ルーティング確認 ----

    @Nested
    @DisplayName("全 6 ドメインのルーティング確認")
    class DomainRoutingTest {

        private void setupSuccessRetry(Long userId, String domain,
                                       org.mockito.stubbing.OngoingStubbing<?> listenerStub) {
            AccountPurgeCompletionStatusEntity entity = buildPendingEntity(userId, domain);
            given(completionStatusRepository.findByUserIdAndDomainName(userId, domain))
                    .willReturn(Optional.of(entity));
        }

        @Test
        @DisplayName("chart ドメインが正しくルーティングされる")
        void chart_ルーティング() {
            Long userId = 700L;
            setupSuccessRetry(userId, "chart", null);
            given(chartPurgeEventListener.retryPurge(userId)).willReturn(true);

            service.retryDomainPurge(userId, "chart");

            verify(chartPurgeEventListener).retryPurge(userId);
        }

        @Test
        @DisplayName("errorreport ドメインが正しくルーティングされる")
        void errorreport_ルーティング() {
            Long userId = 800L;
            setupSuccessRetry(userId, "errorreport", null);
            given(errorReportPurgeEventListener.retryPurge(userId)).willReturn(true);

            service.retryDomainPurge(userId, "errorreport");

            verify(errorReportPurgeEventListener).retryPurge(userId);
        }

        @Test
        @DisplayName("残債1: billing ドメインが正しくルーティングされる")
        void billing_ルーティング() {
            Long userId = 900L;
            setupSuccessRetry(userId, "billing", null);
            given(billingPurgeEventListener.retryPurge(userId)).willReturn(true);

            service.retryDomainPurge(userId, "billing");

            verify(billingPurgeEventListener).retryPurge(userId);
        }
    }
}
