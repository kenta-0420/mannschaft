package com.mannschaft.app.resident.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.resident.repository.PropertyListingInquiryRepository;
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
 * {@link ResidentAnonymizationEventListener} の単体テスト（クロスドメインFK撤廃 第二陣E）。
 *
 * <p>{@code @Async} / {@code @TransactionalEventListener} / {@code @Transactional} の三重アノテーションは
 * Spring プロキシ経由で初めて有効化されるため、単体テストではバイパスされロジック本体のみが評価される。
 * ここでは物件問い合わせの即時削除と、例外を伝播させない安全弁挙動を検証する。</p>
 *
 * <p>※ {@code @Transactional(REQUIRES_NEW)} 欠落等の bean 設定不備は、CI の既存 {@code @SpringBootTest}
 * スイートが本 {@code @Component} を起動時に解決する際に検知される。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResidentAnonymizationEventListener")
class ResidentAnonymizationEventListenerTest {

    @Mock
    private PropertyListingInquiryRepository propertyListingInquiryRepository;

    @InjectMocks
    private ResidentAnonymizationEventListener listener;

    private static final Long USER_ID = 7001L;

    @Nested
    @DisplayName("onUserAnonymized（退会即時・物件問い合わせ削除）")
    class OnUserAnonymized {

        @Test
        @DisplayName("正常系: 物件問い合わせが即時削除される")
        void deletesInquiries() {
            listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "user@example.com"));

            verify(propertyListingInquiryRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("異常系: Repository が例外を投げても外に伝播させない")
        void doesNotPropagateException() {
            doThrow(new RuntimeException("DB error"))
                    .when(propertyListingInquiryRepository).deleteByUserId(USER_ID);

            assertDoesNotThrow(() ->
                    listener.onUserAnonymized(new UserAnonymizedEvent(USER_ID, "fail@example.com")));
        }
    }
}
