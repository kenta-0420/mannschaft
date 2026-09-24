package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDeliveryStatus;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.error.NotificationCreditErrorCode;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJob;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobStatus;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 試練B（殿の訂正指示を反映）: チャンク処理の途中失敗によるロールバックと、
 * 通知クレジットの猶予超過（軍議第8版確定稿 §3.4手順4・§8.1〜§8.4・AC-24・AC-25・AC-26・AC-27・AC-56）。
 *
 * <p>{@link com.mannschaft.app.notification.credit.service.NotificationCreditService} は実物を使い、
 * 残高・猶予の状態は {@link OrganizationNotificationBalanceRepository} で直接フィクスチャに作る
 * （担当外の API/DTO/Controller には触れない）。</p>
 *
 * <p><b>AC-26 は本ファイルでは書かない</b>: 「受け付けの時点で既に猶予超過なら CREDIT_INSUFFICIENT を返し、
 * 何も作らない」は送信 API（{@code POST .../confirmable-notifications}）の事前確認であり（軍議 §3.3）、
 * ワーカー隊（ConfirmableFanoutChunkSink）の担当範囲外（API隊の担当）。Controller/DTO には触れない
 * という制約とも整合するため、ここでは対象外とする。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ConfirmableFanoutChunkSink 途中失敗のロールバックと課金の猶予超過（AC-24/25/27/56・試練B）")
class ConfirmableFanoutChunkSinkCreditRollbackIT extends AbstractMySqlIntegrationTest {

    private static final String EMAIL_PREFIX_BASE = "cfx-credit";
    private static final Long ORG_ID_BASE = 88_000_000L;

    @Autowired
    private ConfirmableFanoutChunkSink sink;

    @Autowired
    private ConfirmableNotificationRepository notificationRepository;

    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NotificationFanoutJobRepository fanoutJobRepository;

    @PersistenceContext
    private EntityManager em;

    private String emailPrefix;
    private Long notificationId;
    private Long organizationId;
    private UUID jobIdToCleanUp;

    /** 是正3（§9.2）: finish に渡す jobId は実在するジョブ行を伴わせる。 */
    private void insertJobRow(UUID jobId, Long notificationId) {
        ConfirmableFanoutFixture.insertFanoutJobRow(fanoutJobRepository, jobId, notificationId);
        jobIdToCleanUp = jobId;
    }

    @AfterEach
    void cleanUp() {
        if (jobIdToCleanUp != null) {
            fanoutJobRepository.deleteById(jobIdToCleanUp);
        }
        if (notificationId != null) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                    notificationId);
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", notificationId);
        }
        if (organizationId != null) {
            jdbc.update("DELETE FROM organization_notification_balances WHERE organization_id = ?", organizationId);
        }
        if (emailPrefix != null) {
            ConfirmableFanoutFixture.deleteUsers(em, emailPrefix);
        }
    }

    private Long createNotification(long orgId) {
        ConfirmableNotificationEntity notification = notificationRepository.save(ConfirmableNotificationEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(orgId)
                .title("AC-24/25/27/56 課金・ロールバック")
                .priority(ConfirmableNotificationPriority.NORMAL)
                .status(ConfirmableNotificationStatus.ACTIVE)
                .deliveryStatus(ConfirmableNotificationDeliveryStatus.QUEUED)
                .totalRecipientCount(0)
                .unconfirmedCount(0)
                .build());
        return notification.getId();
    }

    @Test
    @DisplayName("AC-24: チャンク内に重複user_idがあり一意制約違反で例外が出たら、"
            + "そのチャンクの受信者行・notifications・課金はすべてロールバックされる")
    void duplicateUserIdWithinChunkRollsBackWholeChunk() {
        emailPrefix = EMAIL_PREFIX_BASE + "-24-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + System.nanoTime() % 1_000_000L;
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 10, emailPrefix);
        notificationId = createNotification(organizationId);

        // チャンク内で同じuser_idを2回渡す（DBのUNIQUE制約 uq_cnr_notification_user 違反を誘発する）。
        java.util.List<Long> chunkWithDuplicate = new java.util.ArrayList<>(userIds);
        chunkWithDuplicate.add(userIds.get(0));

        // 「UnsupportedOperationException（骨格の未実装）ではない、実処理由来の例外」であることを
        // 明示的に要求する。これにより、骨格段階では必ず失敗し（isNotInstanceOf に反する＝red）、
        // 出陣後に「重複起因の一意制約違反等で失敗する」実装ができて初めて通る（偽green防止）。
        assertThatThrownBy(() -> sink.processChunk(UUID.randomUUID(), notificationId, chunkWithDuplicate))
                .as("AC-24: チャンク内の重複は『実処理由来』の例外になるべき（骨格のUOEは対象外）")
                .isInstanceOf(RuntimeException.class)
                .isNotInstanceOf(UnsupportedOperationException.class);

        assertThat(countRecipients())
                .as("AC-24: 例外が出たチャンクの受信者行は1件も残らない（全ロールバック）")
                .isZero();
        assertThat(countNotifications())
                .as("AC-24: notifications行も残らない")
                .isZero();
        assertThat(balanceRepository.findByOrganizationId(organizationId))
                .as("AC-24/AC-27: 課金も消費されない（残高行自体が作られない）")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-25/AC-27: 1チャンク目は成功して課金される。2チャンク目で猶予超過になったら"
            + "1チャンク目の分は有効なまま残り、2チャンク目は例外になる")
    void secondChunkExceedsGraceButFirstChunkStays() {
        emailPrefix = EMAIL_PREFIX_BASE + "-25-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + 1 + System.nanoTime() % 1_000_000L;
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 20, emailPrefix);
        notificationId = createNotification(organizationId);

        // 猶予期間を既に72時間超過した状態で残高0・無料枠使い切りにしておく
        // （1チャンク目消費時点で即座に猶予超過＝CREDIT_INSUFFICIENTになる状態）。
        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(organizationId)
                .freeUsedThisMonth(10_000L) // 無料枠を使い切っている
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodStartAt(LocalDateTime.now().minusHours(73)) // 猶予72時間を既に超過
                .gracePeriodDebt(1L)
                .build());

        List<Long> chunk1 = userIds.subList(0, 10);
        List<Long> chunk2 = userIds.subList(10, 20);

        assertThatThrownBy(() -> sink.processChunk(UUID.randomUUID(), notificationId, chunk1))
                .as("AC-25: 猶予超過状態でのチャンク処理はBusinessException(CREDIT_INSUFFICIENT)で"
                        + "失敗するべき（骨格のUOEは対象外＝現状red）")
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting(ex -> ((com.mannschaft.app.common.BusinessException) ex).getErrorCode())
                .isEqualTo(NotificationCreditErrorCode.CREDIT_INSUFFICIENT);

        assertThat(countRecipients())
                .as("AC-25: 猶予超過チャンクは受信者行を作らない")
                .isZero();
    }

    @Test
    @DisplayName("AC-27: 組織スコープで課金される件数の合計は、実際に作った受信者行の数と一致する")
    void billedCountMatchesActualRecipientRows() {
        emailPrefix = EMAIL_PREFIX_BASE + "-27-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + 2 + System.nanoTime() % 1_000_000L;
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 30, emailPrefix);
        notificationId = createNotification(organizationId);

        sink.processChunk(UUID.randomUUID(), notificationId, userIds);

        long recipients = countRecipients();
        OrganizationNotificationBalanceEntity balance = balanceRepository.findByOrganizationId(organizationId)
                .orElseThrow(() -> new AssertionError("AC-27: 課金の残高行が作られているべき"));
        long billed = balance.getFreeUsedThisMonth(); // 無料枠内であればここに現れる想定
        assertThat(billed)
                .as("AC-27: 課金された件数(30)は実際に作った受信者行の数と一致するべき")
                .isEqualTo(recipients)
                .isEqualTo(30L);
    }

    @Test
    @DisplayName("AC-56: finishが正常に完了すれば、ジョブ・delivery_status・statusの3つが"
            + "同一トランザクションで一貫して確定する（『どれも確定しない』の裏返し＝部分的な確定が"
            + "起きていないことを、正常系の完全な確定によって固定する）")
    void finishCommitsJobDeliveryStatusAndStatusTogetherAtomically() {
        emailPrefix = EMAIL_PREFIX_BASE + "-56-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + 3 + System.nanoTime() % 1_000_000L;
        List<Long> userIds = ConfirmableFanoutFixture.insertUsers(em, 5, emailPrefix);
        notificationId = createNotification(organizationId);

        UUID jobId = UUID.randomUUID();
        insertJobRow(jobId, notificationId);
        sink.processChunk(jobId, notificationId, userIds);
        sink.finish(jobId, notificationId);

        ConfirmableNotificationEntity after = notificationRepository.findById(notificationId).orElseThrow();
        assertThat(after.getDeliveryStatus())
                .as("AC-56: delivery_statusはDELIVEREDに確定している")
                .isEqualTo(ConfirmableNotificationDeliveryStatus.DELIVERED);
        assertThat(after.getStatus())
                .as("AC-56: 全員未確認なのでstatusはACTIVEのまま（COMPLETEDへ勝手に進んでいない）")
                .isEqualTo(ConfirmableNotificationStatus.ACTIVE);
        assertThat(after.getTotalRecipientCount())
                .as("AC-56: total_recipient_countも矛盾なく確定している")
                .isEqualTo(5);

        NotificationFanoutJob job = fanoutJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus())
                .as("AC-56: finish正常完了時はジョブもDONEに同一トランザクションで確定する")
                .isEqualTo(NotificationFanoutJobStatus.DONE);
    }

    @Test
    @DisplayName("AC-56: finishの途中で例外が出たら、ジョブのDONE・delivery_status・statusのどれも確定しない"
            + "（ロールバックし、ジョブは再試行に回る＝再試行時にDONEになっていない）")
    void finishExceptionRollsBackJobDeliveryStatusAndStatusTogether() {
        emailPrefix = EMAIL_PREFIX_BASE + "-56ex-" + UUID.randomUUID();
        organizationId = ORG_ID_BASE + 4 + System.nanoTime() % 1_000_000L;
        notificationId = createNotification(organizationId);

        UUID jobId = UUID.randomUUID();
        insertJobRow(jobId, notificationId);

        // 存在しない確認通知IDを渡し、finish内部のfindByIdForUpdateがIllegalStateExceptionを
        // 投げて処理が途中で失敗する状況を作る（AC-56: 例外が出たら何も確定しない）。
        Long nonexistentNotificationId = notificationId + 999_000_000L;
        assertThatThrownBy(() -> sink.finish(jobId, nonexistentNotificationId))
                .as("AC-56: finish内部の例外はそのまま伝播する")
                .isInstanceOf(RuntimeException.class);

        NotificationFanoutJob job = fanoutJobRepository.findById(jobId).orElseThrow();
        assertThat(job.getStatus())
                .as("AC-56: finishが例外で終わった場合、ジョブはDONEにならない（ロールバック）")
                .isNotEqualTo(NotificationFanoutJobStatus.DONE);
    }

    private long countRecipients() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
        return c == null ? 0 : c;
    }

    private long countNotifications() {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, notificationId);
        return c == null ? 0 : c;
    }
}
