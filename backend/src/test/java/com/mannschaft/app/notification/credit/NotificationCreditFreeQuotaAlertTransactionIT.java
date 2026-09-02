package com.mannschaft.app.notification.credit;

import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * Issue #2990 L2 — 無料通知枠アラートのトランザクション境界の実 DB 検証
 * （{@code NotificationCreditService#consume}）。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code consume} は無料枠 9,000 通到達時に {@code sendFreeQuotaAlertAsync} を
 * <b>同一クラス内の無修飾呼び出し</b>で呼んでいた。自己呼び出しは Spring プロキシを経ないため
 * {@code @Async} が失効し、アラート送信は {@code consume} の業務トランザクション内で同期実行される。
 * その先の {@code createNotification} は既定の {@code REQUIRED} 伝播で同じトランザクションに参加するため、
 * 通知の DB 例外は rollback-only を残し、メソッド内の {@code try/catch} で握っても commit 時に
 * <b>クレジット消費（無料枠消費量・月次使用量・アラート送信済フラグ）ごと巻き戻っていた</b>。
 * 実害の入口は {@code DirectMailService#sendMail}（台帳キー
 * {@code DirectMailService#sendMail -> TX_NOTIFY_VIA_DELEGATE | ROLLBACK_COUPLED}）である。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み</h2>
 * <p>{@link NotificationService#createNotification} を spy して例外を投げさせる。ここは
 * <b>是正前・是正後のどちらの経路でも必ず通る</b>ため、是正前のコードでは
 * {@code consume} のコミットが {@code UnexpectedRollbackException} で失敗して残高行が
 * 更新されず（＝本テストは赤）、是正後は AFTER_COMMIT リスナー内の
 * {@code REQUIRES_NEW} に閉じ込められて業務行が残る（＝緑）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むと
 * コミットが起きずリスナーが発火しないまま「巻き戻っていない」ことになり<b>偽の緑</b>になる。
 * よってトランザクションを張らず、フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする
 * （金型: {@code ScheduleCommentNotificationPartialFailureIT}）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L2 無料通知枠アラートのトランザクション境界（実DB）")
class NotificationCreditFreeQuotaAlertTransactionIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private NotificationCreditService notificationCreditService;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 受信者（組織 ADMIN）解決。user_roles のフィクスチャを組まずに配送を成立させるため差し替える。 */
    @MockitoBean
    private RoleService roleService;

    /** 是正前・是正後の双方で必ず通る一点。ここを失敗させて巻き戻りの有無を測る。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @Test
    @DisplayName("無料枠アラートの通知が失敗しても、クレジット消費（残高行）はコミットされる")
    void 通知失敗でもクレジット消費はコミットされる() {
        long organizationId = 970_000_000L + (System.nanoTime() % 1_000_000L);
        LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);

        transactionTemplate.executeWithoutResult(tx -> balanceRepository.save(
                OrganizationNotificationBalanceEntity.builder()
                        .organizationId(organizationId)
                        .freeUsedThisMonth(8_999L)
                        .freeQuotaMonth(firstOfMonth)
                        .alertSentThisMonth(false)
                        .creditBalance(0L)
                        .gracePeriodDebt(0L)
                        .build()));

        given(roleService.getAdminUserIdsByOrganizationId(organizationId))
                .willReturn(List.of(990_000_001L));
        willThrow(new RuntimeException("模擬通知失敗（#2990 L2 検証用）"))
                .given(notificationService).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        notificationCreditService.consume(organizationId, 2, NotificationSourceType.DIRECT_MAIL);

        OrganizationNotificationBalanceEntity saved = transactionTemplate.execute(
                tx -> balanceRepository.findByOrganizationId(organizationId).orElseThrow());
        assertThat(saved.getFreeUsedThisMonth())
                .as("通知が失敗してもクレジット消費は巻き戻らない")
                .isEqualTo(9_001L);
        assertThat(saved.getAlertSentThisMonth())
                .as("アラート送信済フラグも同じトランザクションで確定している")
                .isTrue();

        // AFTER_COMMIT + @Async のリスナーが実際に配送を試み、例外で失敗したことの裏取り（非同期のため待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()));
    }
}
