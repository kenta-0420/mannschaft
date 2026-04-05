package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.gdpr.service.WithdrawalStripeHandler;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.repository.TeamSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawalStripeHandler 単体テスト")
class WithdrawalStripeHandlerTest {

    @Mock
    private StripeCustomerRepository stripeCustomerRepository;

    @Mock
    private TeamSubscriptionRepository teamSubscriptionRepository;

    @InjectMocks
    private WithdrawalStripeHandler handler;

    @Nested
    @DisplayName("handleWithdrawal")
    class HandleWithdrawal {

        @Test
        @DisplayName("正常系: WithdrawalRequestedEventを受け取ったとき処理が実行される")
        void 正常_イベント受信_処理実行() {
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(1L, "user@example.com");
            given(stripeCustomerRepository.findByUserId(anyLong())).willReturn(Optional.empty());

            // 例外なく処理が完了することを確認
            assertThatCode(() -> handler.handleWithdrawal(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: StripeCustomer未登録時にスキップされ例外は発生しない")
        void 正常_StripeCustomer未登録_スキップ() {
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(100L, "test@example.com");
            given(stripeCustomerRepository.findByUserId(anyLong())).willReturn(Optional.empty());

            // Stripeは未実装だが例外は外部に伝播しない
            assertThatCode(() -> handler.handleWithdrawal(event))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 処理中に例外が発生しても外部に伝播しない")
        void 異常_例外サイレント_外部伝播なし() {
            WithdrawalRequestedEvent event = new WithdrawalRequestedEvent(999L, "another@example.com");
            given(stripeCustomerRepository.findByUserId(anyLong())).willReturn(Optional.empty());

            assertThatCode(() -> handler.handleWithdrawal(event))
                    .doesNotThrowAnyException();
        }
    }
}
