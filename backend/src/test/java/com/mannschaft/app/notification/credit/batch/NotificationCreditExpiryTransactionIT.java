package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseEntity;
import com.mannschaft.app.notification.credit.entity.NotificationCreditPurchaseStatus;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.NotificationCreditPurchaseRepository;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L4 — 通知クレジット有効期限バッチのトランザクション境界の実 DB 検証。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code NotificationCreditExpiryBatch#runBatch} は<b>バッチ全体が単一の {@code @Transactional}</b>
 * であり、失効アラート送信 {@code sendCreditExpiredAlertAsync} を<b>同一クラス内の無修飾呼び出し</b>で
 * 呼んでいた。自己呼び出しは Spring プロキシを経ないため {@code @Async} が失効し、通知送信は
 * バッチの業務トランザクション内で同期実行される。その先の {@code createNotification} は既定の
 * {@code REQUIRED} 伝播で同じトランザクションに参加するため、<b>通知の INSERT が DB で失敗すると
 * rollback-only マークが残り</b>、{@code sendCreditExpiredAlertAsync} 内の {@code try/catch} が
 * 例外を握りつぶしても commit 時に {@code UnexpectedRollbackException} となって、
 * <b>クレジット失効処理（{@code expired_at} の設定と、{@code credit_balance} からの失効分の差し引き）
 * ごと巻き戻っていた</b>。台帳上は {@code ASYNC_SELF_INVOCATION}（＝非同期だから安全）に見えていたが、
 * 実態は {@code ROLLBACK_COUPLED} だったことになる。</p>
 *
 * <p>実害は「期限切れクレジットが失効扱いにならず、実際には使えない残高が組織に残り続ける」形で現れる。
 * これは本バッチの {@code @BackgroundFeaturePolicy} が「止めてはならない」理由として挙げている事象
 * そのものであり、バッチは動いているのに結果が毎回巻き戻る、という気づきにくい壊れ方をする。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み — <b>モック例外ではなく実 DB 障害</b></h2>
 * <p>是正前の {@code sendCreditExpiredAlertAsync} は本体全体を {@code try/catch} で包んでいるため、
 * <b>モックが投げる例外では欠陥を再現できない</b>（モックは DB に触れないので rollback-only が立たず、
 * 例外は握りつぶされて終わる）。そこで本 IT は L2 の
 * {@code NotificationCreditFreeQuotaAlertTransactionIT} と同じ手法で、本番コードを一切変えずに
 * {@code notifications} テーブルへ<b>テスト側で CHECK 制約を張る</b>
 * （{@code notification_type} が失効アラートのときだけ INSERT を弾く制約）。
 * これにより失効アラートの INSERT だけが実際に永続化に失敗する。制約は {@code @AfterEach} で
 * 必ず落とすため他テストに影響しない。</p>
 *
 * <p>受信者（組織 ADMIN）の解決は、是正前が {@code UserRoleRepository} を直接、是正後が
 * {@code RoleService} 経由で<b>同じリポジトリメソッド</b>を叩く。両実装が必ず通る一点である
 * {@code findAdminUserIdsByOrganizationId} を spy で固定することで、是正前・是正後のどちらでも
 * 受信者が 1 件解決され、経路の差が出ない（{@code test} プロファイルは Flyway 無効・Entity 由来 DDL で
 * シードが無く、{@code users}/{@code roles}/{@code user_roles} の実フィクスチャを組めないため）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>是正後の通知は項目TXのコミット後に {@code @Async("event-pool")} で発火する。テストメソッドを
 * トランザクションで包むとコミットが起きず、「巻き戻っていない」ことになり<b>偽の緑</b>になる。
 * よってトランザクションを張らず、フィクスチャ投入・検証読み取りは {@link TransactionTemplate} で
 * 明示的にコミットする（金型: {@code ScheduleCommentNotificationPartialFailureIT}）。
 * CHECK 制約の付与・削除は MySQL の DDL であり暗黙コミットを伴うため、トランザクション外で実行する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L4 通知クレジット失効バッチのトランザクション境界（実DB）")
class NotificationCreditExpiryTransactionIT extends AbstractMySqlIntegrationTest {

    /** 失効アラートの通知 INSERT だけを実 DB で失敗させる CHECK 制約の名前。 */
    private static final String BLOCK_CONSTRAINT = "chk_issue2990_l4_block_expired_alert";

    /** CHECK 制約で弾く通知種別。 */
    private static final String BLOCKED_TYPE = "NOTIFICATION_CREDIT_EXPIRED";

    @Autowired
    private NotificationCreditExpiryBatch expiryBatch;

    @Autowired
    private NotificationCreditPurchaseRepository purchaseRepository;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 受信者（組織 ADMIN）解決。是正前（Repository 直叩き）・是正後（RoleService 経由）の
     * 双方がこのメソッドに収束するため、ここを固定すれば経路差が出ない。
     */
    @MockitoSpyBean
    private UserRoleRepository userRoleRepository;

    /** 通知 INSERT が実際に試みられたことの裏取り用（stub しない＝実処理がそのまま走る）。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @BeforeEach
    void 通知テーブルへ実DB障害を仕込む() {
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (notification_type <> '" + BLOCKED_TYPE + "')");
    }

    @AfterEach
    void 仕込んだ制約を必ず落とす() {
        jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
    }

    @Test
    @DisplayName("失効アラートの通知が実DBで永続化に失敗しても、失効処理（残高の差し引き）はコミットされる")
    void 通知の永続化失敗でも失効処理はコミットされる() {
        long organizationId = 940_000_000L + (System.nanoTime() % 1_000_000L);
        long adminUserId = organizationId + 1L;
        long expiredCredits = 300L;
        long initialBalance = 1_000L;

        Long purchaseId = transactionTemplate.execute(tx -> {
            balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                    .organizationId(organizationId)
                    .freeUsedThisMonth(0L)
                    .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                    .alertSentThisMonth(false)
                    .creditBalance(initialBalance)
                    .gracePeriodDebt(0L)
                    .build());
            return purchaseRepository.save(NotificationCreditPurchaseEntity.builder()
                    .organizationId(organizationId)
                    .packageId(1L)
                    .purchasedByUserId(adminUserId)
                    .creditsGranted(expiredCredits)
                    .remainingCredits(expiredCredits)
                    .priceJpy(BigDecimal.valueOf(1000))
                    .paymentStatus(NotificationCreditPurchaseStatus.PAID)
                    .paidAt(LocalDateTime.now().minusDays(400))
                    // 既に期限切れ（失効処理の対象になる）
                    .expiresAt(LocalDateTime.now().minusDays(1))
                    .alertSent30d(true)
                    .alertSent7d(true)
                    .build()).getId();
        });

        given(userRoleRepository.findAdminUserIdsByOrganizationId(organizationId))
                .willReturn(List.of(adminUserId));

        // 是正前はここで UnexpectedRollbackException が抜けてくる（通知 INSERT の rollback-only）。
        assertThatCode(() -> expiryBatch.runBatch())
                .as("通知の永続化失敗が失効処理のトランザクションを巻き戻してはならない")
                .doesNotThrowAnyException();

        NotificationCreditPurchaseEntity saved = transactionTemplate.execute(
                tx -> purchaseRepository.findById(purchaseId).orElseThrow());
        assertThat(saved.getExpiredAt())
                .as("通知が実DBで失敗しても失効フラグ（expired_at）は巻き戻らない")
                .isNotNull();

        OrganizationNotificationBalanceEntity balance = transactionTemplate.execute(
                tx -> balanceRepository.findByOrganizationId(organizationId).orElseThrow());
        assertThat(balance.getCreditBalance())
                .as("失効分が credit_balance から差し引かれた状態でコミットされている")
                .isEqualTo(initialBalance - expiredCredits);

        // 通知配送が実際に試みられたことの裏取り（是正後は @Async のため待つ）。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        eq(adminUserId), eq(BLOCKED_TYPE),
                        any(), any(), any(), any(), any(), any(), any(), any(), any()));

        // 通知は CHECK 制約で 1 件も入っていない（＝握りつぶしではなく本当に永続化が失敗している）。
        Long notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, adminUserId);
        assertThat(notificationCount)
                .as("通知の INSERT は実 DB の CHECK 制約で失敗している")
                .isZero();
    }
}
