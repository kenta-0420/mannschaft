package com.mannschaft.app.payment;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L7 — 未払いリマインド通知のトランザクション境界の実DB検証。
 *
 * <h2>是正前に何が起きていたか</h2>
 * <p>{@code MemberPaymentService#sendRemind}（{@code @Transactional}）は未払いメンバーをループし、
 * その内側で {@code notificationHelper.notify}（→ {@link NotificationService#createNotification}）を
 * try で囲わずに直接呼んでいた。{@code createNotification} は既定の {@code REQUIRED} で
 * {@code sendRemind} のトランザクションに参加するため、1 人ぶんの INSERT が落ちると:</p>
 * <ul>
 *   <li>例外が {@code sendRemind} の外へ抜けてループが打ち切られ、
 *       <b>以降の未払いメンバーには送信すら試みられない</b>。</li>
 *   <li>それまでに書いた通知行も同じトランザクションなので全員ぶん巻き戻る。</li>
 *   <li>管理者には 500 が返り、{@link RemindResponse} すら受け取れない。</li>
 * </ul>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService} を spy し、<b>3 人中 1 人目の宛先だけ</b>失敗させる。
 * {@code NotificationService} は是正前（helper 経由）・是正後（{@code NotificationDeliveryRunner} 経由）の
 * どちらでも必ず通る合流点であり、是正前のコードに対してもそのままコンパイル・実行できる。
 * 是正前は 1 人目で例外が抜けるので {@code sendRemind} 自体が失敗し、残り 2 人への
 * {@code createNotification} は<b>一度も呼ばれない</b>（＝赤くなる理由は
 * 「通知の失敗が業務メソッドを巻き込んでループを止めている」ことそのもの）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の配送は {@code AFTER_COMMIT} で発火する。テストをトランザクションで包むと
 * コミットが起きずリスナーが発火しないまま緑になる（偽の緑）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L7 未払いリマインド通知のトランザクション境界（実DB）")
class PaymentRemindNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberPaymentService memberPaymentService;

    @Autowired
    private PaymentItemRepository paymentItemRepository;

    @Autowired
    private MemberPaymentRepository memberPaymentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FeatureFlagRepository backgroundGateFeatureFlagRepository;

    @Autowired
    private CacheManager backgroundGateCacheManager;

    /** 是正前・是正後の双方で必ず通る合流点。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    /**
     * 配送リスナーの {@code @BackgroundFeaturePolicy(DROP_WHEN_DISABLED)} ゲートを開ける。
     *
     * <p>テストプロファイルは Flyway を無効化しており {@code feature_flags} が空のため、
     * 何もしないと {@code FeatureFlagService#isEnabled} がフェイルクローズで false を返し、
     * イベントが配送されずに正常終了してしまう（＝検証したいメカニズムに到達できない）。</p>
     */
    @BeforeEach
    void openBackgroundFeatureGate() {
        FeatureFlagTestSupport.enable(
                backgroundGateFeatureFlagRepository,
                backgroundGateCacheManager,
                "FEATURE_BILLING_PAYMENT_ENABLED");
    }

    @Test
    @DisplayName("1人目の通知が失敗しても、残りの未払いメンバーへの送信は試みられる")
    void 一人の通知失敗で一括リマインドが止まらない() {
        long base = 960_000_000L + (System.nanoTime() % 1_000_000L);
        long orgId = base;
        long teamId = base + 1L;
        long user1 = base + 11L;
        long user2 = base + 12L;
        long user3 = base + 13L;

        Long itemId = insertPaymentItem(orgId, teamId);
        insertPendingPayment(itemId, user1);
        insertPendingPayment(itemId, user2);
        insertPendingPayment(itemId, user3);

        // 1人目だけ失敗させる。是正前はここで sendRemind ごと落ち、2人目以降は呼ばれない。
        willThrow(new RuntimeException("模擬通知失敗（#2990 L7 検証用）"))
                .given(notificationService).createNotification(
                        eq(user1), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                        anyString(), any(), any(NotificationScopeType.class), any(), any(), any());

        RemindResponse response = memberPaymentService.sendRemind(itemId);
        assertThat(response.getNotifiedCount())
                .as("1人ぶんの通知失敗で一括リマインドの応答が失われてはならない")
                .isEqualTo(3);

        // AFTER_COMMIT + @Async のリスナーが 3 人全員ぶん配送を試みたことの裏取り（非同期のため待つ）。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            for (long userId : List.of(user1, user2, user3)) {
                verify(notificationService, times(1)).createNotification(
                        eq(userId), anyString(), any(NotificationPriority.class), anyString(), anyString(),
                        anyString(), any(), any(NotificationScopeType.class), any(), any(), any());
            }
        });

        // 成功した 2 人ぶんの通知は、失敗した 1 人に巻き込まれず独立してコミットされている。
        Integer stillUnpaid = transactionTemplate.execute(tx -> memberPaymentRepository
                .findUnpaidUserIdsByPaymentItemId(itemId).size());
        assertThat(stillUnpaid)
                .as("リマインドは支払い状態を変えない（業務データ側の副作用が無いことの確認）")
                .isEqualTo(3);
    }

    // ---- フィクスチャ ----

    private Long insertPaymentItem(long orgId, long teamId) {
        return transactionTemplate.execute(tx -> paymentItemRepository.save(
                PaymentItemEntity.builder()
                        .organizationId(orgId)
                        .teamId(teamId)
                        .name("年会費")
                        .type(PaymentItemType.ANNUAL_FEE)
                        .amount(new BigDecimal("10000.00"))
                        .build()).getId());
    }

    private void insertPendingPayment(Long itemId, long userId) {
        transactionTemplate.executeWithoutResult(tx -> memberPaymentRepository.save(
                MemberPaymentEntity.builder()
                        .paymentItemId(itemId)
                        .userId(userId)
                        .amountPaid(BigDecimal.ZERO)
                        .paymentMethod(PaymentMethod.CASH)
                        .status(PaymentStatus.PENDING)
                        .build()));
    }
}
