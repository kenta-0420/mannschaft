package com.mannschaft.app.timeline.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
import com.mannschaft.app.timeline.repository.UserMuteRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TimelineBookmarkAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣E）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の三重アノテーションは
 * Spring プロキシ経由で初めて有効化されるため、単体テストではバイパスされロジック本体のみが評価される。
 * ここではブックマークが30日後の物理削除（{@link AccountPurgedEvent}）で削除されること、
 * および弱側の例外隔離と、強側の例外伝播・コミット後完了を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備は、CI の既存 {@code @SpringBootTest}
 * スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineBookmarkAnonymizationEventListener")
class TimelineBookmarkAnonymizationEventListenerTest {

    @Mock
    private AccountPurgeCompletionService completionService;

    @Mock
    private UserMuteRepository userMuteRepository;

    @Mock
    private TimelineBookmarkRepository timelineBookmarkRepository;
    @Mock private com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository ranchTransport;


    @InjectMocks
    private TimelineBookmarkAnonymizationEventListener listener;

    private static final Long USER_ID = 9003L;

    @BeforeEach
    void prepareOwnerSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearOwnerSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Nested
    @DisplayName("onAccountPurged（退会30日後・ブックマーク削除）")
    class OnAccountPurged {

        @Test
        @DisplayName("正常系: ブックマークが30日後の物理削除で削除される")
        void deletesBookmarks() {
            listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

            verify(completionService, never()).markDomainSuccess(USER_ID, "timeline");
            assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(completionService).markDomainSuccess(USER_ID, "timeline");

            verify(timelineBookmarkRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: 強側の削除失敗を伝播し、完了callbackを登録しない")
        void propagatesStrongPurgeFailure() {
            doThrow(new RuntimeException("DB error"))
                    .when(timelineBookmarkRepository).deleteByUserId(USER_ID);

            assertThrows(RuntimeException.class, () ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));

            assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
            verify(completionService, never()).markDomainSuccess(USER_ID, "timeline");
        }
    }

    @Test
    @DisplayName("手動retryは削除のみ実行し、完了callbackは登録しない")
    void retryDoesNotReportCompletionBeforeProxyCommit() {
        assertThat(listener.retryPurge(USER_ID)).isTrue();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        verify(completionService, never()).markDomainSuccess(USER_ID, "timeline");
    }
}
