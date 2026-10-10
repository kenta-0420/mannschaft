package com.mannschaft.app.reflection;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.reflection.service.ReflectionSpacedReminderService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Issue #2997 G8 — {@code ReflectionSpacedReminderService#processDueReminders} の送信済み状態の契約（共通AC-D）を実DBで固定する。
 *
 * <p>焦点は AC-D-3: 「通知は成功したが SENT の保存に失敗した」とき、<b>永続状態は PENDING のまま残り、
 * 次回の実行で再送される</b>。永続状態は別TX（{@link TransactionTemplate}）から読み直して確認する。</p>
 *
 * <p><b>是正前の実装（メソッド全体を 1 つの {@code @Transactional} で包む形）では落ちる理由</b>:
 * 是正前は due 行が同一TXの managed エンティティだった。{@code markAsSent()} でメモリ上の status が SENT になった時点で
 * dirty になり、{@code save} が例外を投げても（本テストは save の呼び出しを失敗させる）、外側TXのコミット時に
 * flush されて SENT が永続化される。つまり「保存に失敗したのに SENT になる」ため、PENDING が残らず再送もされない
 * （通知は出たのに印も付く＝本テストの期待と逆）。さらに通知側のDB例外で rollback-only になると、
 * 他の項目の SENT もコミット時に巻き戻る。現在は行を TX 外で読み detached のまま扱うので、save が失敗すれば何も永続化されない。</p>
 *
 * <p>クラスに {@code @Transactional} は付けない（付けると外側TXが残り、本テストが検証したい「項目ごとの独立確定」を覆い隠す）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Issue #2997 G8 振り返りリマインダーの送信済み状態の契約（実DB）")
class ReflectionDueReminderSentStateIT extends AbstractMySqlIntegrationTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Autowired private ReflectionSpacedReminderService reminderService;
    @Autowired private ReflectionThemeRepository themeRepository;
    @Autowired private ReflectionEntryRepository entryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoSpyBean private ReflectionSpacedReminderRepository reminderRepository;

    @Test
    @DisplayName("AC-D-3: 通知成功・SENT保存失敗なら永続状態は PENDING のまま残り、次回の実行で再送されて SENT になる")
    void sentSaveFailureLeavesPendingAndIsResentNextRun() {
        Long userId = insertUser("refl-d3-" + System.nanoTime() + "@example.com");
        UUID reminderId = insertDueReminder(userId);
        AtomicBoolean failSentSave = new AtomicBoolean(true);
        // 対象行の SENT 保存だけを失敗させる（他テストが残した due 行には影響させない）。
        doAnswer(inv -> {
            ReflectionSpacedReminderEntity arg = inv.getArgument(0);
            if (failSentSave.get() && reminderId.equals(arg.getId())
                    && arg.getStatus() == ReflectionReminderStatus.SENT) {
                throw new RuntimeException("SENT の保存失敗（模擬）");
            }
            // Spring Data の Repository は JDK 動的プロキシで、@MockitoSpyBean はインターフェース型の mock の既定 Answer を
            // delegatesTo(元の Bean) にする。callRealMethod() は抽象メソッドで "Cannot call abstract real method" になり、
            // 2 回目の保存まで失敗して SENT 検証が落ちるため、既定 Answer へ委譲する
            // （前例: member/MemberTxBoundaryITSupport#callReal）。
            return Mockito.mockingDetails(inv.getMock()).getMockCreationSettings().getDefaultAnswer().answer(inv);
        }).when(reminderRepository).save(any(ReflectionSpacedReminderEntity.class));

        reminderService.processDueReminders();

        // 1 回目: 通知は 1 件出ているが、永続状態は PENDING のまま（別TXから読み直す）。
        assertThat(statusOf(reminderId)).as("保存に失敗したので SENT は永続化されていない")
                .isEqualTo(ReflectionReminderStatus.PENDING);
        assertThat(notificationCountOf(userId)).as("通知自体は成功している").isEqualTo(1);

        // 2 回目: 保存が通るようにして再実行すると、PENDING の行が再送されて SENT になる（二重送信を許容する契約）。
        failSentSave.set(false);
        reminderService.processDueReminders();

        assertThat(statusOf(reminderId)).isEqualTo(ReflectionReminderStatus.SENT);
        assertThat(notificationCountOf(userId)).as("再送された").isEqualTo(2);

        // 3 回目: SENT 済みなので再送されない。
        reminderService.processDueReminders();
        assertThat(notificationCountOf(userId)).as("SENT 済みには再送しない").isEqualTo(2);
    }

    private UUID insertDueReminder(Long userId) {
        return transactionTemplate.execute(tx -> {
            ReflectionThemeEntity theme = themeRepository.save(ReflectionThemeEntity.builder()
                    .userId(userId).title("数学").build());
            ReflectionEntryEntity entry = entryRepository.save(ReflectionEntryEntity.builder()
                    .themeId(theme.getId()).userId(userId).targetDate(LocalDate.now())
                    .structuredContent("{}").build());
            return reminderRepository.save(ReflectionSpacedReminderEntity.builder()
                    .entryId(entry.getId()).userId(userId)
                    .remindAt(LocalDateTime.now(JST).minusMinutes(1))
                    .intervalDays(1).kind(ReflectionReminderKind.SPACED)
                    .status(ReflectionReminderStatus.PENDING).build()).getId();
        });
    }

    private ReflectionReminderStatus statusOf(UUID reminderId) {
        return transactionTemplate.execute(tx ->
                reminderRepository.findById(reminderId).orElseThrow().getStatus());
    }

    private long notificationCountOf(Long userId) {
        return transactionTemplate.execute(tx -> notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 50)).getContent().stream()
                .filter((NotificationEntity n) -> "REFLECTION_RECALL_REMINDER".equals(n.getNotificationType()))
                .count());
    }

    private Long insertUser(String email) {
        return transactionTemplate.execute(tx -> userRepository.save(UserEntity.builder()
                .email(email)
                .lastName("通知試験")
                .firstName("太郎")
                .displayName("通知試験ユーザー")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build()).getId());
    }
}
