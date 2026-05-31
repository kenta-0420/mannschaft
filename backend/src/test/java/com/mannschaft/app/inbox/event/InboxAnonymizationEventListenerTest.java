package com.mannschaft.app.inbox.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * F04.11 {@link InboxAnonymizationEventListener} 単体テスト。
 *
 * <p>一陣が本体実装済みのため <b>green 想定</b>。{@code UserAnonymizedEvent} 受信で 3 表の
 * {@code deleteAllByUserId} が呼ばれることを検証する（手本: {@code FavoriteAnonymizationEventListenerTest}）。
 * 設計書 04_security_operations.md §1.4（弱匿名化＝即時物理削除）。</p>
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} は Spring プロキシ
 * 経由でのみ有効化されるため、単体ではバイパスされロジック本体のみが評価される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InboxAnonymizationEventListener")
class InboxAnonymizationEventListenerTest {

    @Mock
    private InboxItemStateRepository itemStateRepository;

    @Mock
    private NotificationLabelRepository labelRepository;

    @Mock
    private InboxLabelLinkRepository labelLinkRepository;

    @InjectMocks
    private InboxAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: 3 表すべての deleteAllByUserId が呼ばれる")
        void deletesAllThreeTablesByUserId() {
            var event = new UserAnonymizedEvent(42L, "user@example.com");

            listener.handleUserAnonymized(event);

            verify(labelLinkRepository).deleteAllByUserId(42L);
            verify(itemStateRepository).deleteAllByUserId(42L);
            verify(labelRepository).deleteAllByUserId(42L);
        }

        @Test
        @DisplayName("例外系: Repository が例外を投げても外へ伝播させない")
        void doesNotPropagateException() {
            var event = new UserAnonymizedEvent(99L, "fail@example.com");
            doThrow(new RuntimeException("DB error")).when(labelLinkRepository).deleteAllByUserId(99L);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
