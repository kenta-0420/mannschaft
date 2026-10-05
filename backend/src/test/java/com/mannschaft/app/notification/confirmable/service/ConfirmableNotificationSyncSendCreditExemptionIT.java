package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CMP-260930-1932 AC-2: 同期 {@code send}（ORG・実ユーザー作成者）は通知クレジットを消費しない。
 *
 * <p>{@link ConfirmableNotificationService} と {@code NotificationCreditService} は実物を使い、
 * 組織の残高行を直接フィクスチャで作る（{@link ConfirmableFanoutChunkSinkCreditRollbackIT} と同じ流儀）。
 * 境界: 猶予72hちょうど・72h+1s・残高0（猶予未開始）・無料枠内 のいずれでも送信は成功し、
 * {@code organization_notification_balances} 行・{@code notification_monthly_usage} が前後で不変であること。</p>
 *
 * <p>クラスに {@code @Transactional} は付けない（{@code send} 自身の TX をコミットさせて実 DB の状態を見る）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-1932 AC-2: 同期 send は組織クレジットを消費しない（実DB）")
class ConfirmableNotificationSyncSendCreditExemptionIT extends AbstractMySqlIntegrationTest {

    private static final Long ORG_ID_BASE = 89_000_000L;

    @Autowired
    private ConfirmableNotificationService confirmableNotificationService;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long organizationId;
    private final List<Long> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : notificationIds) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?", id);
            jdbc.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?", id);
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", id);
        }
        if (organizationId != null) {
            jdbc.update("DELETE FROM confirmable_notifications WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                    organizationId);
            jdbc.update("DELETE FROM notification_monthly_usage WHERE organization_id = ?", organizationId);
            jdbc.update("DELETE FROM organization_notification_balances WHERE organization_id = ?", organizationId);
            jdbc.update("DELETE FROM confirmable_notification_settings WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                    organizationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, emailPrefix);
        }
    }

    @Test
    @DisplayName("AC-2: 猶予開始からちょうど72h（境界）・残高0 の組織でも同期 send は成功し、残高行・月次使用量は不変")
    void AC2_猶予72hちょうど残高0でも送信成功し残高不変() {
        assertSendDoesNotTouchCredit(LocalDateTime.now().minusHours(72), 10_000L, "72h");
    }

    @Test
    @DisplayName("AC-2: 猶予開始から72h+1s（超過）・残高0 の組織でも同期 send は成功し、残高行・月次使用量は不変")
    void AC2_猶予72h1秒超過残高0でも送信成功し残高不変() {
        assertSendDoesNotTouchCredit(LocalDateTime.now().minusHours(72).minusSeconds(1), 10_000L, "72h1s");
    }

    @Test
    @DisplayName("AC-2: 無料枠使い切り・残高0・猶予未開始の組織でも、同期 send は猶予を開始させない（残高行不変）")
    void AC2_残高0猶予未開始でも猶予を開始させない() {
        assertSendDoesNotTouchCredit(null, 10_000L, "zero");
    }

    @Test
    @DisplayName("AC-2: 無料枠内の組織でも、同期 send は無料枠使用量を増やさない（残高行・月次使用量不変）")
    void AC2_無料枠内でも無料枠使用量を増やさない() {
        assertSendDoesNotTouchCredit(null, 0L, "free");
    }

    private void assertSendDoesNotTouchCredit(LocalDateTime gracePeriodStartAt, long freeUsed, String label) {
        emailPrefix = "cmp1932-ac2-" + label + "-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + System.nanoTime() % 1_000_000L;
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(transactionManager, em, 3, emailPrefix);
        Long creatorUserId = userIds.get(0);
        List<Long> recipients = userIds.subList(1, 3);

        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(organizationId)
                .freeUsedThisMonth(freeUsed)
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodStartAt(gracePeriodStartAt)
                .gracePeriodDebt(gracePeriodStartAt == null ? 0L : 1L)
                .build());

        BalanceSnapshot before = snapshot();
        long usageRowsBefore = countMonthlyUsageRows();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = confirmableNotificationService.send(
                ScopeType.ORGANIZATION, organizationId, "CMP-260930-1932 AC-2 " + label, "本文",
                ConfirmableNotificationPriority.NORMAL, null,
                null, null, null, null, null, null, null,
                creatorUserId, recipients));
        if (created[0] != null) {
            notificationIds.add(created[0].getId());
        }

        assertThat(thrown)
                .as("AC-2(%s): システム発の同期 send は組織クレジットの状態にかかわらず成功する"
                        + "（現状は consume が CREDIT_INSUFFICIENT を投げるか、課金して残高を動かす）", label)
                .isNull();
        assertThat(countRecipients(created[0].getId()))
                .as("AC-2(%s): 受信者行が作られている", label)
                .isEqualTo(2L);
        assertThat(snapshot())
                .as("AC-2(%s): organization_notification_balances 行（無料枠使用量・残高・猶予開始・猶予負債）は前後で不変", label)
                .isEqualTo(before);
        assertThat(countMonthlyUsageRows())
                .as("AC-2(%s): notification_monthly_usage に行が増えない", label)
                .isEqualTo(usageRowsBefore);
    }

    private record BalanceSnapshot(Long freeUsedThisMonth, Long creditBalance,
                                   LocalDateTime gracePeriodStartAt, Long gracePeriodDebt) {
    }

    private BalanceSnapshot snapshot() {
        return new TransactionTemplate(transactionManager).execute(status -> balanceRepository
                .findByOrganizationId(organizationId)
                .map(b -> new BalanceSnapshot(b.getFreeUsedThisMonth(), b.getCreditBalance(),
                        b.getGracePeriodStartAt(), b.getGracePeriodDebt()))
                .orElse(null));
    }

    private long countMonthlyUsageRows() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_monthly_usage WHERE organization_id = ?", Long.class, organizationId);
        return c == null ? 0 : c;
    }

    private long countRecipients(Long notificationId) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        return c == null ? 0 : c;
    }
}
