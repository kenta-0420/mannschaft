package com.mannschaft.app.onboarding;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import com.mannschaft.app.onboarding.service.OnboardingReminderBatchService;
import com.mannschaft.app.onboarding.service.OnboardingReminderRunner;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Issue #2834 / CMP-056 第2群ロット2 — バッチ単位トランザクションと通知分離の実 DB 検証
 * （{@link OnboardingReminderBatchService}）。
 *
 * <h2>この IT で実証すること</h2>
 * <ul>
 *   <li><b>AC-1</b>: 1 進捗ぶんの処理が失敗しても、<b>後続進捗のリマインド確定はコミットされる</b>。
 *       是正前はバッチ全体が 1 トランザクションだったため、1 件の失敗が rollback-only を残し
 *       全進捗ぶんが巻き戻っていた。</li>
 *   <li><b>AC-2</b>: 通知配送（{@link NotificationDeliveryRunner#sendOne}）が例外を投げても、
 *       業務処理（{@code last_reminded_at} の記録）はコミットされる。</li>
 *   <li><b>AC-3</b>: 業務トランザクションがロールバックした場合、通知は作られない
 *       （通知は {@code AFTER_COMMIT} で発火するため）。</li>
 *   <li><b>AC-4</b>: 再実行しても安全（冪等）。2 回目の実行では同日ぶんを二度記録せず、
 *       通知も追加で作られない。</li>
 *   <li><b>AC-5</b>: コミット後に通知が実際に作られる（deny されない）。</li>
 * </ul>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code AFTER_COMMIT} で発火する。テストメソッドをトランザクションで包むとコミットが
 * 起きず通知が 1 件も作られないまま「作られないことを確認できた」という<b>偽の緑</b>になる。よって
 * トランザクションを張らず、フィクスチャ投入・検証読み取りは {@link TransactionTemplate} で
 * 明示的にコミットする（第2群ロット1 {@code QuickMemoReminderBatchTransactionIT} と同型）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Import(OnboardingReminderBatchTransactionIT.RollbackTriggerConfig.class)
@DisplayName("Issue #2834/CMP-056 バッチ単位TXと通知分離の実DB検証（OnboardingReminderBatchService）")
class OnboardingReminderBatchTransactionIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired
    private OnboardingReminderBatchService batchService;

    @Autowired
    private OnboardingProgressRepository progressRepository;

    @Autowired
    private OnboardingTemplateRepository templateRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** AC-2 / AC-3 検証用 spy。{@code NotificationService} は実 Bean のまま差し替えない。 */
    @MockitoSpyBean
    private NotificationDeliveryRunner notificationDeliveryRunner;

    /** AC-1 検証用 spy。特定進捗ぶんだけ失敗させる。 */
    @MockitoSpyBean
    private OnboardingReminderRunner onboardingReminderRunner;

    @AfterEach
    void disarmRollbackTrigger() {
        RollbackTrigger.armed = false;
    }

    @Test
    @DisplayName("AC-1: 1進捗ぶんが失敗しても、後続進捗のリマインド確定はコミットされる")
    void 一件失敗しても後続進捗はコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long failingUserId = insertUser("ob-ac1-ng-" + nonce + "@example.com");
        Long healthyUserId = insertUser("ob-ac1-ok-" + nonce + "@example.com");
        Long templateId = insertTemplate(failingUserId);
        Long failingProgressId = insertOverdueProgress(templateId, failingUserId);
        Long healthyProgressId = insertOverdueProgress(templateId, healthyUserId);

        willThrow(new RuntimeException("模擬リマインド確定失敗（AC-1検証用）"))
                .given(onboardingReminderRunner).remindOne(eq(failingProgressId), any(), any());

        batchService.processReminders();

        // 本丸: 失敗した進捗の巻き添えで成功側まで消えない。
        assertThat(lastRemindedAt(healthyProgressId))
                .as("1進捗の失敗が後続進捗のコミットを巻き戻さない")
                .isNotNull();
        assertThat(lastRemindedAt(failingProgressId))
                .as("失敗した進捗ぶんは記録されない")
                .isNull();
    }

    @Test
    @DisplayName("AC-2: 通知配送のDB例外が起きてもリマインド確定はコミットされる")
    void 通知配送が失敗してもリマインド確定はコミットされる() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("ob-ac2-" + nonce + "@example.com");
        Long templateId = insertTemplate(userId);
        Long progressId = insertOverdueProgress(templateId, userId);

        willThrow(new RuntimeException("模擬通知配送失敗（AC-2検証用）"))
                .given(notificationDeliveryRunner).sendOne(any());

        batchService.processReminders();

        assertThat(lastRemindedAt(progressId))
                .as("通知配送が失敗してもリマインド確定は巻き戻らない")
                .isNotNull();

        // AFTER_COMMIT + @Async のリスナーが Runner を呼び、例外で配送は失敗する（非同期のため待つ）。
        // 他のフィクスチャ由来の進捗が同時に対象になることがあるため件数は固定しない。
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                verify(notificationDeliveryRunner, atLeastOnce()).sendOne(any()));
    }

    @Test
    @DisplayName("AC-3: 業務トランザクションがロールバックした場合、通知は作られない")
    void 業務トランザクションのロールバック時は通知が作られない() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("ob-ac3-" + nonce + "@example.com");
        Long templateId = insertTemplate(userId);
        Long progressId = insertOverdueProgress(templateId, userId);

        // BEFORE_COMMIT のリスナーで例外を投げ、Runner の独立トランザクションをロールバックさせる。
        // AFTER_COMMIT の配送リスナーはコミットしなければ発火しない、という因果を実測する。
        RollbackTrigger.armed = true;

        batchService.processReminders();

        assertThat(lastRemindedAt(progressId))
                .as("業務トランザクションがロールバックしたので記録も残らない")
                .isNull();
        verify(notificationDeliveryRunner, never()).sendOne(any());
    }

    @Test
    @DisplayName("AC-4/AC-5: コミット後に通知が作られ、再実行しても二重に送らない")
    void 通知が作られ再実行しても二重送信しない() {
        String nonce = String.valueOf(System.nanoTime());
        Long userId = insertUser("ob-ac4-" + nonce + "@example.com");
        Long templateId = insertTemplate(userId);
        Long progressId = insertOverdueProgress(templateId, userId);

        batchService.processReminders();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> notifications = transactionTemplate.execute(
                    tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                            userId, PageRequest.of(0, 10)).getContent());
            assertThat(notifications)
                    .as("オンボーディング期限超過通知が deny されずに作られていること")
                    .anyMatch(n -> "ONBOARDING_OVERDUE".equals(n.getNotificationType()));
        });

        LocalDateTime firstRemindedAt = lastRemindedAt(progressId);
        assertThat(firstRemindedAt).isNotNull();

        // 2 回目の実行: 同日中は既に記録済みなので確定せず、通知も追加されない。
        batchService.processReminders();

        assertThat(lastRemindedAt(progressId))
                .as("同日中の再実行ではリマインド時刻が書き換わらない")
                .isEqualTo(firstRemindedAt);
        List<NotificationEntity> after = transactionTemplate.execute(
                tx -> notificationRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(0, 10)).getContent());
        assertThat(after.stream().filter(n -> "ONBOARDING_OVERDUE".equals(n.getNotificationType())).count())
                .as("再実行で二重通知が作られない")
                .isEqualTo(1L);
    }

    private LocalDateTime lastRemindedAt(Long progressId) {
        return transactionTemplate.execute(
                tx -> progressRepository.findById(progressId).orElseThrow().getLastRemindedAt());
    }

    /** 期限を過ぎた IN_PROGRESS な進捗を 1 件作成し id を返す。 */
    private Long insertOverdueProgress(Long templateId, Long userId) {
        return transactionTemplate.execute(tx -> progressRepository.save(OnboardingProgressEntity.builder()
                .templateId(templateId)
                .userId(userId)
                .scopeType("TEAM")
                .scopeId(1L)
                .status(OnboardingProgressStatus.IN_PROGRESS)
                .totalSteps((short) 3)
                .completedSteps((short) 1)
                .deadlineAt(LocalDateTime.now(JST).minusDays(1))
                .build()).getId());
    }

    /** onboarding_progresses.template_id は onboarding_templates への FK を持つため実行のたびに 1 件作る。 */
    private Long insertTemplate(Long createdBy) {
        return transactionTemplate.execute(tx -> templateRepository.save(OnboardingTemplateEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .name("IT用テンプレート")
                .status(OnboardingTemplateStatus.ACTIVE)
                .reminderDaysBefore((short) 3)
                .createdBy(createdBy)
                .version(0L)
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
                .lastName("導入試験")
                .firstName("太郎")
                .displayName("導入試験ユーザー")
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
        public void onEvent(OnboardingReminderNotificationEvent event) {
            if (armed) {
                throw new IllegalStateException("強制ロールバック（AC-3検証用）");
            }
        }
    }
}
