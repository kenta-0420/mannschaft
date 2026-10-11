package com.mannschaft.app.actionmemo.event;

import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
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
 * {@link ActionMemoAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣D）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の
 * 三重アノテーションは Spring プロキシ経由で初めて有効化されるため、単体テストでは
 * バイパスされロジック本体のみが評価される。ここでは二層削除（即時=行動メモ本体 /
 * 30日=タグ・ユーザー設定）の振り分けと、弱側の例外隔離と、強側の例外伝播・コミット後完了を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備（AFTER_COMMIT で
 * 素の REQUIRED を指定すると ApplicationContext がロード不能になる事故）は、CI の
 * 既存 {@code @SpringBootTest} スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoAnonymizationEventListener")
class ActionMemoAnonymizationEventListenerTest {

    @Mock
    private AccountPurgeCompletionService completionService;

    @Mock
    private ActionMemoRepository actionMemoRepository;

    @Mock
    private ActionMemoTagRepository actionMemoTagRepository;

    @Mock
    private UserActionMemoSettingsRepository userActionMemoSettingsRepository;

    @InjectMocks
    private ActionMemoAnonymizationEventListener listener;

    private static final Long USER_ID = 5151L;

    @BeforeEach
    void prepareOwnerSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearOwnerSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("onUserAnonymized（退会即時・行動メモ本体削除）")
    class OnUserAnonymized {

        @Test
        @DisplayName("正常系: 行動メモ本体のみが即時削除され、タグ・ユーザー設定には触れない")
        void deletesOnlyActionMemos() {
            listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "user@example.com"));

            verify(actionMemoRepository).deleteAllByUserIdIncludingDeleted(USER_ID);
            verify(actionMemoTagRepository, never()).deleteAllByUserIdIncludingDeleted(USER_ID);
            verify(userActionMemoSettingsRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(actionMemoRepository).deleteAllByUserIdIncludingDeleted(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "fail@example.com")));
        }
    }

    @Nested
    @DisplayName("onAccountPurged（退会30日後・タグ／ユーザー設定削除）")
    class OnAccountPurged {

        @Test
        @DisplayName("正常系: タグ・ユーザー設定のみが削除され、行動メモ本体には触れない")
        void deletesOnlyTagsAndSettings() {
            listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

            verify(completionService, never()).markDomainSuccess(USER_ID, "actionmemo");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(completionService).markDomainSuccess(USER_ID, "actionmemo");

            verify(actionMemoTagRepository).deleteAllByUserIdIncludingDeleted(USER_ID);
            verify(userActionMemoSettingsRepository).deleteByUserId(USER_ID);
            verify(actionMemoRepository, never()).deleteAllByUserIdIncludingDeleted(USER_ID);
        }

        @Test
        @DisplayName("異常系: 強側の削除失敗を伝播し、完了callbackを登録しない")
        void propagatesStrongPurgeFailure() {
            doThrow(new RuntimeException("DB error"))
                    .when(actionMemoTagRepository).deleteAllByUserIdIncludingDeleted(USER_ID);

            assertThrows(RuntimeException.class, () ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(completionService, never()).markDomainSuccess(USER_ID, "actionmemo");
        }
    }

    @Test
    @DisplayName("手動retryは削除のみ実行し、完了callbackは登録しない")
    void retryDoesNotReportCompletionBeforeProxyCommit() {
        assertThat(listener.retryPurge(USER_ID)).isTrue();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(completionService, never()).markDomainSuccess(USER_ID, "actionmemo");
    }
}
