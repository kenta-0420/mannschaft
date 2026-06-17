package com.mannschaft.app.timeline.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
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
 * {@link TimelineBookmarkAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣E）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の三重アノテーションは
 * Spring プロキシ経由で初めて有効化されるため、単体テストではバイパスされロジック本体のみが評価される。
 * ここではブックマークが30日後の物理削除（{@link AccountPurgedEvent}）で削除されること、
 * および例外を伝播させない安全弁挙動を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備は、CI の既存 {@code @SpringBootTest}
 * スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimelineBookmarkAnonymizationEventListener")
class TimelineBookmarkAnonymizationEventListenerTest {

    @Mock
    private TimelineBookmarkRepository timelineBookmarkRepository;

    @InjectMocks
    private TimelineBookmarkAnonymizationEventListener listener;

    private static final Long USER_ID = 9003L;

    @Nested
    @DisplayName("onAccountPurged（退会30日後・ブックマーク削除）")
    class OnAccountPurged {

        @Test
        @DisplayName("正常系: ブックマークが30日後の物理削除で削除される")
        void deletesBookmarks() {
            listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

            verify(timelineBookmarkRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(timelineBookmarkRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));
        }
    }
}
