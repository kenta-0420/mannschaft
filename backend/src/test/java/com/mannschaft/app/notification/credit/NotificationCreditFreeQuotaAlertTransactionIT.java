package com.mannschaft.app.notification.credit;

import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDate;
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
 * Issue #2990 L2 — 無料通知枠アラートのトランザクション境界の実 DB 検証
 * （{@code NotificationCreditService#consume}）。
 *
 * <h2>是正前に何が巻き戻っていたか</h2>
 * <p>{@code consume} は無料枠 9,000 通到達時に {@code sendFreeQuotaAlertAsync} を
 * <b>同一クラス内の無修飾呼び出し</b>で呼んでいた。自己呼び出しは Spring プロキシを経ないため
 * {@code @Async} が失効し、アラート送信は {@code consume} の業務トランザクション内で同期実行される。
 * その先の {@code createNotification} は既定の {@code REQUIRED} 伝播で同じトランザクションに参加するため、
 * <b>通知の INSERT が DB で失敗すると rollback-only マークが残り</b>、
 * {@code sendFreeQuotaAlertAsync} 内の {@code try/catch} が例外を握りつぶしても、
 * commit 時に {@code UnexpectedRollbackException} となって
 * <b>クレジット消費（無料枠消費量・月次使用量・アラート送信済フラグ）ごと巻き戻っていた</b>。
 * 実害の入口は {@code DirectMailService#sendMail}（台帳キー
 * {@code DirectMailService#sendMail -> TX_NOTIFY_VIA_DELEGATE | ROLLBACK_COUPLED}）であり、
 * 「無料枠が 9,000 通に達した回の一斉メールだけが、消費記録ごと消える」形で現れる。</p>
 *
 * <h2>この IT が欠陥を捕まえる仕組み — <b>モック例外ではなく実 DB 障害</b></h2>
 * <p>是正前の {@code sendFreeQuotaAlertAsync} は本体全体を {@code try/catch} で包んでいるため、
 * <b>モックが投げる例外では欠陥を再現できない</b>（モックは DB に触れないので rollback-only が立たず、
 * 例外は握りつぶされて終わる）。そこで本 IT は本番コードを一切変えずに、
 * {@code notifications} テーブルへ<b>テスト側で CHECK 制約を張る</b>:</p>
 * <pre>{@code
 * ALTER TABLE notifications ADD CONSTRAINT chk_issue2990_l2_block_credit_alert
 *   CHECK (notification_type <> 'NOTIFICATION_CREDIT_ALERT')
 * }</pre>
 * <p>これにより無料枠アラートの INSERT だけが MySQL エラー 3819（check constraint violated）で
 * <b>実際に永続化に失敗</b>する。制約は {@code @AfterEach} で必ず落とすため他テストに影響しない。</p>
 *
 * <p>受信者（組織 ADMIN）の解決は、是正前が
 * {@code UserRoleRepository#findAdminUserIdsByOrganizationId} を直接、是正後が
 * {@code RoleService#getAdminUserIdsByOrganizationId} 経由で同じクエリを叩く。
 * <b>両実装が必ず通る一点</b>であるこのリポジトリメソッドを spy で固定することで、
 * 是正前・是正後のどちらでも受信者が 1 件解決され、経路の差が出ない
 * （{@code test} プロファイルは Flyway 無効・Entity 由来 DDL でシードが無く、
 * {@code users}/{@code roles}/{@code user_roles} の実フィクスチャを組めないため）。</p>
 *
 * <h2>期待される差と実測結果（2026-09-03 実測）</h2>
 * <ul>
 *   <li><b>是正前</b>（{@code NotificationCreditService} だけを {@code 722a1d6926^} の版へ戻して実行）:
 *       tests=1 / skipped=0 / <b>failures=1</b>。失敗の中身は
 *       {@code UnexpectedRollbackException: Transaction silently rolled back because it has been
 *       marked as rollback-only} が
 *       {@code NotificationCreditService$$SpringCGLIB$$0.consume} の
 *       {@code @Transactional} 境界から抜けてくる形であり、<b>狙いどおり「通知の永続化失敗が
 *       業務トランザクションを巻き戻す」という #2990 本体の欠陥そのもので赤い</b>。</li>
 *   <li><b>是正後</b>（現行コード）: tests=1 / skipped=0 / failures=0。{@code consume} は正常終了し、
 *       無料枠消費 9,001 通・アラート送信済フラグ true がコミットされる。通知の INSERT は
 *       AFTER_COMMIT + {@code @Async("event-pool")} のリスナー配下、
 *       {@code NotificationDeliveryRunner#sendOne}（REQUIRES_NEW）の中で同じ CHECK 制約に当たり
 *       {@code Check constraint 'chk_issue2990_l2_block_credit_alert' is violated} で失敗するが、
 *       業務トランザクションはもう巻き戻らない。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むと
 * コミットが起きずリスナーが発火しないまま「巻き戻っていない」ことになり<b>偽の緑</b>になる。
 * よってトランザクションを張らず、フィクスチャ投入・検証読み取りは
 * {@link TransactionTemplate} で明示的にコミットする
 * （金型: {@code ScheduleCommentNotificationPartialFailureIT}）。
 * なお CHECK 制約の付与・削除は MySQL の DDL であり暗黙コミットを伴うため、
 * トランザクション外（{@code @BeforeEach} / {@code @AfterEach}）で実行する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2990 L2 無料通知枠アラートのトランザクション境界（実DB）")
class NotificationCreditFreeQuotaAlertTransactionIT extends AbstractMySqlIntegrationTest {

    /** 無料枠アラートの通知 INSERT だけを実 DB で失敗させる CHECK 制約の名前。 */
    private static final String BLOCK_CONSTRAINT = "chk_issue2990_l2_block_credit_alert";

    @Autowired
    private NotificationCreditService notificationCreditService;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 受信者（組織 ADMIN）解決。是正前（Repository 直叩き）・是正後（RoleService 経由）の
     * 双方がこのメソッドに収束するため、ここを固定すれば経路差が出ない。
     * spy なので他のクエリは実 DB のまま動く。
     */
    @MockitoSpyBean
    private UserRoleRepository userRoleRepository;

    /** 通知 INSERT が実際に試みられたことの裏取り用（stub しない＝実処理がそのまま走る）。 */
    @MockitoSpyBean
    private NotificationService notificationService;

    @BeforeEach
    void 通知テーブルへ実DB障害を仕込む() {
        jdbcTemplate.execute("ALTER TABLE notifications ADD CONSTRAINT " + BLOCK_CONSTRAINT
                + " CHECK (notification_type <> 'NOTIFICATION_CREDIT_ALERT')");
    }

    @AfterEach
    void 仕込んだ制約を必ず落とす() {
        jdbcTemplate.execute("ALTER TABLE notifications DROP CHECK " + BLOCK_CONSTRAINT);
    }

    @Test
    @DisplayName("無料枠アラートの通知が実DBで永続化に失敗しても、クレジット消費（残高行）はコミットされる")
    void 通知の永続化失敗でもクレジット消費はコミットされる() {
        long organizationId = 970_000_000L + (System.nanoTime() % 1_000_000L);
        long adminUserId = organizationId + 1L;
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

        given(userRoleRepository.findAdminUserIdsByOrganizationId(organizationId))
                .willReturn(List.of(adminUserId));

        // 是正前はここで UnexpectedRollbackException が抜けてくる（通知 INSERT の rollback-only）。
        assertThatCode(() -> notificationCreditService
                .consume(organizationId, 2, NotificationSourceType.DIRECT_MAIL))
                .as("通知の永続化失敗が業務トランザクションを巻き戻してはならない")
                .doesNotThrowAnyException();

        OrganizationNotificationBalanceEntity saved = transactionTemplate.execute(
                tx -> balanceRepository.findByOrganizationId(organizationId).orElseThrow());
        assertThat(saved.getFreeUsedThisMonth())
                .as("通知が実DBで失敗してもクレジット消費は巻き戻らない")
                .isEqualTo(9_001L);
        assertThat(saved.getAlertSentThisMonth())
                .as("アラート送信済フラグも同じトランザクションで確定している")
                .isTrue();

        // AFTER_COMMIT + @Async のリスナーが実際に配送を試みたことの裏取り（非同期のため待つ）。
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationService, atLeastOnce()).createNotification(
                        eq(adminUserId), eq("NOTIFICATION_CREDIT_ALERT"),
                        any(), any(), any(), any(), any(), any(), any(), any(), any()));

        // 通知は CHECK 制約で 1 件も入っていない（＝握りつぶしではなく本当に永続化が失敗している）。
        Long notificationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class, adminUserId);
        assertThat(notificationCount)
                .as("通知の INSERT は実 DB の CHECK 制約で失敗している")
                .isZero();
    }
}
