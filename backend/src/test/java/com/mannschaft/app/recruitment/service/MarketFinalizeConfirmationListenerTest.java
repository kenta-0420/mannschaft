package com.mannschaft.app.recruitment.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.event.MarketListingReachedFullEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-260930-1932 AC-7 / AC-8: {@link MarketFinalizeConfirmationListener} 単体テスト。
 *
 * <p>送信内容は {@link MarketFinalizeService#planFinalizeConfirmation(Long)} の結果をそのまま
 * {@code sendFromSource(MARKET_FINALIZE, ...)} へ渡すこと、送らない判断なら送らないこと、
 * 送信失敗は伝播させず listingId 入りの ERROR ログにすることを固定する。
 * TX境界（AFTER_COMMIT）の実挙動は {@code MarketFinalizeNotificationTransactionIT} が実DBで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-260930-1932 AC-7/AC-8: MarketFinalizeConfirmationListener 単体テスト")
class MarketFinalizeConfirmationListenerTest {

    private static final Long LISTING_ID = 500L;

    @Mock
    private MarketFinalizeService marketFinalizeService;

    @Mock
    private ConfirmableNotificationService confirmableNotificationService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @InjectMocks
    private MarketFinalizeConfirmationListener listener;

    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(MarketFinalizeConfirmationListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
    }

    private static MarketFinalizeService.FinalizeConfirmationPlan teamPlan() {
        return new MarketFinalizeService.FinalizeConfirmationPlan(
                LISTING_ID, ScopeType.TEAM, 88L, "t", "b",
                "/market/listings/" + LISTING_ID, 7L,
                List.of(101L, 102L));
    }

    @Test
    @DisplayName("送るべき札なら、計画どおり MARKET_FINALIZE として sendFromSource する")
    void 計画どおりMARKET_FINALIZEで送る() {
        given(marketFinalizeService.planFinalizeConfirmation(LISTING_ID)).willReturn(Optional.of(teamPlan()));
        given(marketFinalizeService.finalizeConfirmationPriority()).willReturn(ConfirmableNotificationPriority.HIGH);

        listener.onReachedFull(new MarketListingReachedFullEvent(LISTING_ID));

        verify(confirmableNotificationService).sendFromSource(
                eq(MarketFinalizeService.SOURCE_TYPE_MARKET_FINALIZE), eq(LISTING_ID),
                eq(ScopeType.TEAM), eq(88L), eq("t"), eq("b"),
                eq(ConfirmableNotificationPriority.HIGH), isNull(),
                eq("/market/listings/" + LISTING_ID), eq(7L), eq(List.of(101L, 102L)));
    }

    @Test
    @DisplayName("Codex P2: 札の行ロック付き判定と通知作成は、同じ1つの REQUIRES_NEW 通知TXの中で行い、その後にコミットする")
    void 判定と作成は同一の通知TXで行う() {
        given(marketFinalizeService.planFinalizeConfirmation(LISTING_ID)).willReturn(Optional.of(teamPlan()));

        listener.onReachedFull(new MarketListingReachedFullEvent(LISTING_ID));

        InOrder order = inOrder(transactionManager, marketFinalizeService, confirmableNotificationService);
        order.verify(transactionManager).getTransaction(
                org.mockito.ArgumentMatchers.argThat((TransactionDefinition d) ->
                        d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(marketFinalizeService).planFinalizeConfirmation(LISTING_ID);
        order.verify(confirmableNotificationService).sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("Codex P2: 送信が失敗したら通知TXをロールバックし（コミットしない）、ERROR ログで伝播させない")
    void 送信失敗なら通知TXをロールバック() {
        given(marketFinalizeService.planFinalizeConfirmation(LISTING_ID)).willReturn(Optional.of(teamPlan()));
        given(confirmableNotificationService.sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("模擬送信失敗"));

        assertThatCode(() -> listener.onReachedFull(new MarketListingReachedFullEvent(LISTING_ID)))
                .doesNotThrowAnyException();

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("AC-8: 最新状態が FULL でない等で送らない判断なら送らない")
    void 送らない判断なら送らない() {
        given(marketFinalizeService.planFinalizeConfirmation(LISTING_ID)).willReturn(Optional.empty());

        listener.onReachedFull(new MarketListingReachedFullEvent(LISTING_ID));

        verifyNoInteractions(confirmableNotificationService);
    }

    @Test
    @DisplayName("AC-8: 送信が例外を投げても伝播させず、listingId 入りの ERROR ログを1行出す")
    void 送信失敗はERRORログで伝播しない() {
        given(marketFinalizeService.planFinalizeConfirmation(LISTING_ID)).willReturn(Optional.of(teamPlan()));
        given(confirmableNotificationService.sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("模擬送信失敗"));

        assertThatCode(() -> listener.onReachedFull(new MarketListingReachedFullEvent(LISTING_ID)))
                .doesNotThrowAnyException();

        List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage()).contains(String.valueOf(LISTING_ID));
    }
}
