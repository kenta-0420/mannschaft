package com.mannschaft.app.quickmemo;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.event.QuickMemoReminderNotificationEvent;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.service.QuickMemoReminderBatchService;
import com.mannschaft.app.quickmemo.service.QuickMemoReminderRunner;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 第2群ロット1 — バッチ単位トランザクションと通知分離の実 DB 検証
 * （{@code QuickMemoReminderBatchService}）。
 *
 * <h2>この IT で実証すること</h2>
 * <ul>
 *   <li><b>AC-1</b>: 1 ユーザーぶんの処理が失敗しても、<b>後続ユーザーのリマインド確定はコミットされる</b>。
 *       是正前はバッチ全体が 1 トランザクションだったため、1 件の失敗が rollback-only を残し
 *       全ユーザーぶんが巻き戻っていた。</li>
 *   <li><b>AC-2</b>: 通知配送（{@link NotificationDeliveryRunner#sendOne}）が例外を投げても、
 *       業務処理（{@code reminder_1_sent_at} の記録）はコミットされる。</li>
 *   <li><b>AC-3</b>: 業務トランザクションがロールバックした場合、通知は作られない
 *       （通知は {@code AFTER_COMMIT} で発火するため）。</li>
 *   <li><b>AC-4</b>: 再実行しても安全（冪等）。2 回目の実行では同じ枠を二度記録せず、
 *       通知配送も追加で走らない。</li>
 *   <li><b>AC-5</b>: コミット後に通知が実際に作られる（deny されない）。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず通知が 1 件も作られないまま「作られないことを確認できた」という<b>偽の緑</b>になる。よって
 * トランザクションを張らず、フィクスチャ投入・検証読み取りは {@link TransactionTemplate} で
 * 明示的にコミットする（第1群 {@code ContactInviteUsedNotificationTransactionIT} と同型）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(QuickMemoReminderBatchTransactionIT.RollbackTriggerConfig.class)
@DisplayName("Issue #2834/CMP-056 バッチ単位TXと通知分離の実DB検証（QuickMemoReminderBatchService）")
class QuickMemoReminderBatchTransactionIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired
    private QuickMemoReminderBatchService batchService;

    @Autowired
    private QuickMemoRepository memoRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** AC-2 / AC-3 検証用 spy。{@code NotificationService} は実 Bean のまま差し替えない。 */
    @MockitoSpyBean
    private NotificationDeliveryRunner notificationDeliveryRunner;

    /** AC-1 検証用 spy。特定ユーザーぶんだけ失敗させる。 */
    @MockitoSpyBean
    private QuickMemoReminderRunner quickMemoReminderRunner;

    @AfterEach
    void disarmRollbackTrigger() {
        RollbackTrigger.armed = false;
    }

    @Test
    @DisplayName("AC-1: 1ユーザーぶんが失敗しても、後続ユーザーのリマインド確定はコミットされる")
    void 一件失敗しても後続ユーザーはコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long failingUserId = insertUser("qm-ac1-ng-" + nonce + "@example.com");
        Long healthyUserId = insertUser("qm-ac1-ok-" + nonce + "@example.com");
        Long failingMemoId = insertDueMemo(failingUserId, "失敗側メモ");
        Long healthyMemoId = insertDueMemo(healthyUserId, "成功側メモ");

        willThrow(new RuntimeException("模擬リマインド確定失敗（AC-1検証用）"))
                .given(quickMemoReminderRunner).markRemindersSent(eq(failingUserId), anyList(), any());

        batchService.execute();

        // 本丸: 失敗したユーザーの巻き添えで成功側まで消えない。
        assertThat(reminder1SentAt(healthyMemoId))
                .as("1ユーザーの失敗が後続ユーザーのコミットを巻き戻さない")
                .isNotNull();
        assertThat(reminder1SentAt(failingMemoId))
                .as("失敗したユーザーぶんは記録されない")
                .isNull();
    }

    @Test
    @DisplayName("AC-2/AC-5: 通知配送のDB例外が起きてもリマインド確定はコミットされる")
    void 通知配送が失敗してもリマインド確定はコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("qm-ac2-" + nonce + "@example.com");
        Long memoId = insertDueMemo(userId, "通知失敗メモ");

        willThrow(new RuntimeException("模擬通知配送失敗（AC-2検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.execute();

        assertThat(reminder1SentAt(memoId))
                .as("通知配送が失敗してもリマインド確定は巻き戻らない")
                .isNotNull();

        // AFTER_COMMIT + @Async のリスナーが Runner を呼び、例外で配送は失敗する（非同期のため待つ）。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner).sendOne(any()));
    }

    @Test
    @DisplayName("AC-3: 業務トランザクションがロールバックした場合、通知は作られない")
    void 業務トランザクションのロールバック時は通知が作られない() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("qm-ac3-" + nonce + "@example.com");
        Long memoId = insertDueMemo(userId, "ロールバックメモ");

        // BEFORE_COMMIT のリスナーで例外を投げ、Runner の独立トランザクションをロールバックさせる。
        // AFTER_COMMIT の配送リスナーはコミットしなければ発火しない、という因果を実測する。
        RollbackTrigger.armed = true;

        batchService.execute();

        assertThat(reminder1SentAt(memoId))
                .as("業務トランザクションがロールバックしたので記録も残らない")
                .isNull();
        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("AC-4/AC-5: コミット後に通知が作られ、再実行しても二重に送らない")
    void 通知が作られ再実行しても二重送信しない() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("qm-ac4-" + nonce + "@example.com");
        Long memoId = insertDueMemo(userId, "冪等メモ");

        batchService.execute();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = transactionTemplate.execute(
                    tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                            userId, PageRequest.of(0, 10)).getContent());
            assertThat(notifications)
                    .as("ポイっとメモリマインド通知が deny されずに作られていること")
                    .anyMatch(n -> "QUICK_MEMO_REMINDER".equals(n.getNotificationType()));
        });

        LocalDateTime firstSentAt = reminder1SentAt(memoId);
        assertThat(firstSentAt).isNotNull();

        // 2 回目の実行: 既に記録済みなので対象に挙がらず、通知も追加されない。
        batchService.execute();

        assertThat(reminder1SentAt(memoId))
                .as("再実行しても送信済み時刻は書き換わらない")
                .isEqualTo(firstSentAt);
        List<NotificationEntity> after = transactionTemplate.execute(
                tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(0, 10)).getContent());
        assertThat(after.stream().filter(n -> "QUICK_MEMO_REMINDER".equals(n.getNotificationType())).count())
                .as("再実行で二重通知が作られない")
                .isEqualTo(1L);
    }

    private LocalDateTime reminder1SentAt(Long memoId) {
        return transactionTemplate.execute(
                tx -> memoRepository.findById(memoId).orElseThrow().getReminder1SentAt());
    }

    /** リマインド期限が到来済みのポイっとメモを 1 件作成し id を返す。 */
    private Long insertDueMemo(Long userId, String title) {
        return transactionTemplate.execute(tx -> memoRepository.save(QuickMemoEntity.builder()
                .userId(userId)
                .title(title)
                .reminder1ScheduledAt(LocalDateTime.now(JST).minusMinutes(10))
                .build()).getId());
    }

    /**
     * ACTIVE な users 行を 1 件作成し id を返す。
     *
     * <p>実 DDL の NOT NULL 制約は test プロファイルの {@code ddl-auto: create} では再現せず
     * 実 DB で初めて落ちるため、{@code @Builder.Default} を持たない必須フィールドを明示する
     * （第1群 IT と同じ埋め方）。</p>
     */
    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("メモ試験")
                .firstName("太郎")
                .displayName("メモ試験ユーザー")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .contactApprovalRequired(false)
                .build()).getId());
    }

    /** AC-3 用: Runner の独立トランザクションをコミット直前に落とすための試験用リスナー。 */
    @TestConfiguration
    static class RollbackTriggerConfig {
        @org.springframework.context.annotation.Bean
        RollbackTrigger rollbackTrigger() {
            return new RollbackTrigger();
        }
    }

    /**
     * {@code BEFORE_COMMIT} で例外を投げると、そのトランザクションはロールバックされ
     * {@code AFTER_COMMIT} のリスナーは発火しない。これを使って「業務がロールバックすれば
     * 通知は作られない」を実 DB で実測する。
     *
     * <p>常に投げると他のテストまで壊れるため {@link #armed} で明示的に武装する。
     * {@code @Component} は付けない（テストクラス配下も component scan の対象になり得るため、
     * 本 IT 以外の Spring テストにまでリスナーが混入する）。登録は {@link RollbackTriggerConfig} 経由のみ。</p>
     */
    static class RollbackTrigger {
        static volatile boolean armed = false;

        @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
        public void onEvent(QuickMemoReminderNotificationEvent event) {
            if (armed) {
                throw new IllegalStateException("強制ロールバック（AC-3検証用）");
            }
        }
    }
}
