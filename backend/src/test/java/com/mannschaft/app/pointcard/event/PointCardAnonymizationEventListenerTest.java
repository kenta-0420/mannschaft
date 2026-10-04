package com.mannschaft.app.pointcard.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PointCardAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣C）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の
 * 三重アノテーションは Spring プロキシ経由で初めて有効化されるため、単体テストでは
 * バイパスされロジック本体のみが評価される。ここでは二層削除（即時=保有カード /
 * 30日=グループ・ユーザー設定）の振り分けと、弱側の例外隔離と、強側の例外伝播・コミット後完了を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備（AFTER_COMMIT で
 * 素の REQUIRED を指定すると ApplicationContext がロード不能になる事故）は、CI の
 * 既存 {@code @SpringBootTest} スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardAnonymizationEventListener")
class PointCardAnonymizationEventListenerTest {

    @Mock
    private AccountPurgeCompletionService completionService;

    @Mock
    private UserPointCardRepository userPointCardRepository;

    @Mock
    private PointCardGroupRepository pointCardGroupRepository;

    @Mock
    private PointCardUserSettingsRepository pointCardUserSettingsRepository;

    @InjectMocks
    private PointCardAnonymizationEventListener listener;

    private static final Long USER_ID = 4242L;

    @BeforeEach
    void prepareOwnerSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearOwnerSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("onUserAnonymized（退会即時・保有カード削除）")
    class OnUserAnonymized {

        @Test
        @DisplayName("正常系: 保有カードのみが即時削除され、グループ・ユーザー設定には触れない")
        void deletesOnlyUserPointCards() {
            listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "user@example.com"));

            verify(userPointCardRepository).deleteByUserId(USER_ID);
            verify(pointCardGroupRepository, never()).deleteByUserId(USER_ID);
            verify(pointCardUserSettingsRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(userPointCardRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "fail@example.com")));
        }
    }

    @Nested
    @DisplayName("onAccountPurged（退会30日後・グループ／ユーザー設定削除）")
    class OnAccountPurged {

        @Test
        @DisplayName("正常系: グループ・ユーザー設定のみが削除され、保有カードには触れない")
        void deletesOnlyGroupsAndSettings() {
            listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

            verify(completionService, never()).markDomainSuccess(USER_ID, "pointcard");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(completionService).markDomainSuccess(USER_ID, "pointcard");

            verify(pointCardGroupRepository).deleteByUserId(USER_ID);
            verify(pointCardUserSettingsRepository).deleteByUserId(USER_ID);
            verify(userPointCardRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: 強側の削除失敗を伝播し、完了callbackを登録しない")
        void propagatesStrongPurgeFailure() {
            doThrow(new RuntimeException("DB error"))
                    .when(pointCardGroupRepository).deleteByUserId(USER_ID);

            assertThrows(RuntimeException.class, () ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(completionService, never()).markDomainSuccess(USER_ID, "pointcard");
        }
    }

    @Test
    @DisplayName("手動retryは削除のみ実行し、完了callbackは登録しない")
    void retryDoesNotReportCompletionBeforeProxyCommit() {
        assertThat(listener.retryPurge(USER_ID)).isTrue();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(completionService, never()).markDomainSuccess(USER_ID, "pointcard");
    }
}
