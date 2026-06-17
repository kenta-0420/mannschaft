package com.mannschaft.app.pointcard.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.pointcard.repository.PointCardGroupRepository;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import com.mannschaft.app.pointcard.repository.UserPointCardRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PointCardAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣C）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の
 * 三重アノテーションは Spring プロキシ経由で初めて有効化されるため、単体テストでは
 * バイパスされロジック本体のみが評価される。ここでは二層削除（即時=保有カード /
 * 30日=グループ・ユーザー設定）の振り分けと、例外を伝播させない安全弁挙動を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備（AFTER_COMMIT で
 * 素の REQUIRED を指定すると ApplicationContext がロード不能になる事故）は、CI の
 * 既存 {@code @SpringBootTest} スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PointCardAnonymizationEventListener")
class PointCardAnonymizationEventListenerTest {

    @Mock
    private UserPointCardRepository userPointCardRepository;

    @Mock
    private PointCardGroupRepository pointCardGroupRepository;

    @Mock
    private PointCardUserSettingsRepository pointCardUserSettingsRepository;

    @InjectMocks
    private PointCardAnonymizationEventListener listener;

    private static final Long USER_ID = 4242L;

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

            verify(pointCardGroupRepository).deleteByUserId(USER_ID);
            verify(pointCardUserSettingsRepository).deleteByUserId(USER_ID);
            verify(userPointCardRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(pointCardGroupRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));
        }
    }
}
