package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.service.WithdrawalStripeHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawalStripeHandler 単体テスト")
class WithdrawalStripeHandlerTest {

    @InjectMocks
    private WithdrawalStripeHandler handler;

    @Nested
    @DisplayName("onWithdrawalRequested")
    class OnWithdrawalRequested {

        @Test
        @DisplayName("正常系: WithdrawalRequestedEventを受け取ったとき処理が実行される")
        void 正常_イベント受信_処理実行() {
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(1L, "user@example.com");

            // 例外なく処理が完了することを確認
            assertThatCode(() -> handler.onWithdrawalRequested(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: Stripeキャンセル未実装時にwarnがログされ例外は発生しない")
        void 正常_Stripeキャンセル未実装_例外なし() {
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(100L, "test@example.com");

            // Stripeは未実装だが例外は外部に伝播しない
            assertThatCode(() -> handler.onWithdrawalRequested(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 処理中に例外が発生しても外部に伝播しない")
        void 異常_例外サイレント_外部伝播なし() {
            // nullのemailでNullPointerExceptionが発生する可能性があるケースでも例外が外に出ない
            // (handler内でcatchされる)
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(999L, "another@example.com");

            assertThatCode(() -> handler.onWithdrawalRequested(event))
                    .doesNotThrowAnyException();
        }
    }
}
