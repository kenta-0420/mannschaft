package com.mannschaft.app.favorite.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
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
 * Phase W-A 改修後の {@link FavoriteAnonymizationEventListener} のユニットテスト。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の
 * 三重アノテーション化に伴い、リスナーメソッドを直接呼び出して挙動を検証する。
 * （アノテーションは Spring プロキシ経由で初めて有効化されるため、
 * 単体テストでは @Async 等はバイパスされ、ロジック本体のみが評価される。）</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteAnonymizationEventListener")
class FavoriteAnonymizationEventListenerTest {

    @Mock
    private UserFavoriteRepository userFavoriteRepository;

    @InjectMocks
    private FavoriteAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: ユーザーIDに紐づくお気に入りが全件削除される")
        void deletesAllFavoritesByUserId() {
            var event = new UserAnonymizedEvent(42L, "user@example.com");

            listener.handleUserAnonymized(event);

            verify(userFavoriteRepository).deleteAllByUserId(42L);
        }

        @Test
        @DisplayName("例外系: Repositoryが例外を投げてもRuntimeExceptionを外に伝播させない")
        void doesNotPropagateException() {
            var event = new UserAnonymizedEvent(99L, "fail@example.com");
            doThrow(new RuntimeException("DB error")).when(userFavoriteRepository).deleteAllByUserId(99L);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
