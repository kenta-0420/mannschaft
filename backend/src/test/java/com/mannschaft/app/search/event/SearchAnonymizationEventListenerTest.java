package com.mannschaft.app.search.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
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
 * {@link SearchAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣B）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の
 * 三重アノテーションは Spring プロキシ経由で初めて有効化されるため、単体テストでは
 * バイパスされロジック本体のみが評価される。ここでは二層削除（即時=検索履歴 /
 * 30日=保存済みクエリ）の振り分けと、例外を伝播させない安全弁挙動を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備（AFTER_COMMIT で
 * 素の REQUIRED を指定すると ApplicationContext がロード不能になる事故）は、CI の
 * 既存 {@code @SpringBootTest} スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchAnonymizationEventListener")
class SearchAnonymizationEventListenerTest {

    @Mock
    private SearchHistoryRepository searchHistoryRepository;

    @Mock
    private SearchSavedQueryRepository searchSavedQueryRepository;

    @InjectMocks
    private SearchAnonymizationEventListener listener;

    private static final Long USER_ID = 4242L;

    @Nested
    @DisplayName("onUserAnonymized（退会即時・検索履歴削除）")
    class OnUserAnonymized {

        @Test
        @DisplayName("正常系: 検索履歴のみが即時削除され、保存済みクエリには触れない")
        void deletesOnlySearchHistory() {
            listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "user@example.com"));

            verify(searchHistoryRepository).deleteByUserId(USER_ID);
            verify(searchSavedQueryRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(searchHistoryRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "fail@example.com")));
        }
    }

    @Nested
    @DisplayName("onAccountPurged（退会30日後・保存済みクエリ削除）")
    class OnAccountPurged {

        @Test
        @DisplayName("正常系: 保存済みクエリのみが削除され、検索履歴には触れない")
        void deletesOnlySavedQueries() {
            listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

            verify(searchSavedQueryRepository).deleteByUserId(USER_ID);
            verify(searchHistoryRepository, never()).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(searchSavedQueryRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")));
        }
    }
}
