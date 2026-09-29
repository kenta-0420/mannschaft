package com.mannschaft.app.recruitment;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import com.mannschaft.app.recruitment.service.RecruitmentNoShowConfirmBatch;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** NO_SHOW 確定から GLOBAL ペナルティ・確認通知までの MySQL トランザクション検証。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecruitmentNoShowConfirmPenaltyIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_TYPE = "RECRUITMENT_PENALTY";

    @Autowired private RecruitmentNoShowConfirmBatch batch;
    @Autowired private RecruitmentPenaltyService penaltyService;
    @Autowired private RecruitmentNoShowRecordRepository noShowRepository;
    @Autowired private RecruitmentListingRepository listingRepository;
    @Autowired private RecruitmentPenaltySettingRepository settingRepository;
    @Autowired private RecruitmentUserPenaltyRepository penaltyRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long userId;
    private Long teamId;
    private Long listingId;
    private Long participantId;
    private Long recordId;
    private Long settingId;
    private Long competingTeamId;
    private Long competingSettingId;
    private Long penaltyId;

    @AfterEach
    void cleanup() {
        if (penaltyId != null) {
            jdbcTemplate.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' "
                    + "AND source_id IN (SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?)",
                    SOURCE_TYPE, penaltyId);
            jdbcTemplate.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id "
                    + "IN (SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?)",
                    SOURCE_TYPE, penaltyId);
            jdbcTemplate.update("DELETE FROM confirmable_notifications WHERE source_type = ? AND source_id = ?",
                    SOURCE_TYPE, penaltyId);
            penaltyRepository.deleteById(penaltyId);
        }
        if (recordId != null) noShowRepository.deleteById(recordId);
        if (participantId != null) jdbcTemplate.update("DELETE FROM recruitment_participants WHERE id = ?", participantId);
        if (listingId != null) listingRepository.deleteById(listingId);
        if (competingSettingId != null) settingRepository.deleteById(competingSettingId);
        if (settingId != null) settingRepository.deleteById(settingId);
        if (competingTeamId != null) {
            jdbcTemplate.update("DELETE FROM confirmable_notification_settings WHERE scope_type = 'TEAM' AND scope_id = ?",
                    competingTeamId);
            jdbcTemplate.update("DELETE FROM teams WHERE id = ?", competingTeamId);
        }
        if (teamId != null) {
            jdbcTemplate.update("DELETE FROM confirmable_notification_settings WHERE scope_type = 'TEAM' AND scope_id = ?", teamId);
            jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        if (userId != null) jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void committedConfirmationCreatesOneGlobalPenaltyAndOneConfirmableNotification() {
        prepareNoShow();
        RecruitmentNoShowConfirmBatch target = AopTestUtils.getUltimateTargetObject(batch);

        transactionTemplate.executeWithoutResult(status -> target.confirmNoShows());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed FROM recruitment_no_show_records WHERE id = ?", Boolean.class, recordId)).isTrue();
        List<RecruitmentUserPenaltyEntity> penalties = penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(penalties).hasSize(1);
        RecruitmentUserPenaltyEntity penalty = penalties.getFirst();
        penaltyId = penalty.getId();
        assertThat(penalty.getScopeType()).isEqualTo(RecruitmentScopeType.GLOBAL);
        assertThat(penalty.getScopeId()).isNull();
        assertThat(penaltyRepository.findApplicableActivePenaltyExpiry(
                userId, RecruitmentScopeType.ORGANIZATION, 999_999_999L,
                LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE)))
                .isEqualTo(penalty.getExpiresAt());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countConfirmableNotifications()).isEqualTo(1));
        transactionTemplate.executeWithoutResult(status -> target.confirmNoShows());
        assertThat(penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
        assertThat(countConfirmableNotifications()).isEqualTo(1);
    }

    @Test
    void concurrentGlobalEvaluationFromDifferentTeamsCreatesOnePenaltyAndOneDeliveredNotification() throws Exception {
        prepareNoShow();
        prepareCompetingGlobalSetting();
        transactionTemplate.executeWithoutResult(status -> {
            RecruitmentNoShowRecordEntity record = noShowRepository.findById(recordId).orElseThrow();
            record.confirm();
            noShowRepository.saveAndFlush(record);
        });

        jdbcTemplate.update(
                "UPDATE recruitment_no_show_records SET dispute_resolution = 'REVOKED' WHERE id = ?", recordId);
        assertThat(noShowRepository.countConfirmedNoShowsForPenalty(
                userId, 180, true, RecruitmentScopeType.TEAM.name(), teamId)).isZero();
        jdbcTemplate.update(
                "UPDATE recruitment_no_show_records SET dispute_resolution = NULL WHERE id = ?", recordId);
        assertThat(noShowRepository.countConfirmedNoShowsForPenalty(
                userId, 180, true, RecruitmentScopeType.TEAM.name(), teamId)).isEqualTo(1);

        TransactionTemplate firstTransaction = new TransactionTemplate(transactionManager);
        TransactionTemplate secondTransaction = new TransactionTemplate(transactionManager);
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                startBarrier.await(10, TimeUnit.SECONDS);
                firstTransaction.executeWithoutResult(status ->
                        penaltyService.evaluateAndApplyPenalty(userId, RecruitmentScopeType.TEAM, teamId));
                return null;
            });
            Future<?> second = executor.submit(() -> {
                startBarrier.await(10, TimeUnit.SECONDS);
                secondTransaction.executeWithoutResult(status ->
                        penaltyService.evaluateAndApplyPenalty(
                                userId, RecruitmentScopeType.TEAM, competingTeamId));
                return null;
            });

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        List<RecruitmentUserPenaltyEntity> penalties = penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(penalties).hasSize(1);
        RecruitmentUserPenaltyEntity penalty = penalties.getFirst();
        penaltyId = penalty.getId();
        assertThat(penalty.getScopeType()).isEqualTo(RecruitmentScopeType.GLOBAL);
        assertThat(penalty.getScopeId()).isNull();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(countConfirmableNotifications()).isEqualTo(1);
            assertThat(countConfirmableNotificationRecipients()).isEqualTo(1);
            assertThat(countDeliveredNotifications()).isEqualTo(1);
        });
    }

    @Test
    void rollbackKeepsNoShowUnconfirmedAndCreatesNeitherPenaltyNorNotification() {
        prepareNoShow();
        RecruitmentNoShowConfirmBatch target = AopTestUtils.getUltimateTargetObject(batch);

        transactionTemplate.executeWithoutResult(status -> {
            target.confirmNoShows();
            status.setRollbackOnly();
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed FROM recruitment_no_show_records WHERE id = ?", Boolean.class, recordId)).isFalse();
        assertThat(penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId)).isEmpty();
        assertThat(countConfirmableNotificationsForUser()).isZero();
    }

    private void prepareNoShow() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String email = "cmp019-wave16-" + suffix + "@example.test";
        jdbcTemplate.update("""
                INSERT INTO users (email, last_name, first_name, display_name, status,
                    is_searchable, handle_searchable, contact_approval_required, online_visibility,
                    dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted,
                    follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, 'CMP019', 'Wave16', 'CMP019 Wave16', 'ACTIVE',
                    1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, email);
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        String slug = "cmp019-wave16-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "CMP019 Wave16 " + suffix, slug);
        teamId = jdbcTemplate.queryForObject("SELECT id FROM teams WHERE slug = ?", Long.class, slug);

        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).build();
        setting.update(true, 1, 180, 30, PenaltyApplyScope.ALL_SCOPES, false, 30);
        settingId = settingRepository.save(setting).getId();

        LocalDateTime start = LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).plusDays(10).withNano(0);
        listingId = transactionTemplate.execute(status -> listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).categoryId(1L)
                .title("CMP-019 Wave16 NO_SHOW penalty")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start).endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1)).autoCancelAt(start.minusDays(2))
                .capacity(10).minCapacity(1).status(RecruitmentListingStatus.OPEN)
                .createdBy(userId).build()).getId());

        participantId = transactionTemplate.execute(status -> {
            entityManager.createNativeQuery("""
                    INSERT INTO recruitment_participants
                        (listing_id, participant_type, user_id, applied_by, status, applied_at, status_changed_at)
                    VALUES (:listingId, 'USER', :userId, :userId, 'CONFIRMED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """)
                    .setParameter("listingId", listingId).setParameter("userId", userId).executeUpdate();
            return ((Number) entityManager.createNativeQuery("""
                    SELECT id FROM recruitment_participants WHERE listing_id = :listingId AND user_id = :userId
                    """)
                    .setParameter("listingId", listingId).setParameter("userId", userId)
                    .getSingleResult()).longValue();
        });
        recordId = noShowRepository.save(RecruitmentNoShowRecordEntity.builder()
                .participantId(participantId).listingId(listingId).userId(userId)
                .reason(NoShowReason.ADMIN_MARKED).recordedBy(userId).build()).getId();
        // 生 JDBC は Hibernate の UTC 変換を通らないため、DB の UTC 壁時計で経過時間を作る。
        jdbcTemplate.update("""
                UPDATE recruitment_no_show_records
                SET recorded_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 25 HOUR) WHERE id = ?
                """, recordId);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT TIMESTAMPDIFF(HOUR, recorded_at, UTC_TIMESTAMP(6))
                FROM recruitment_no_show_records WHERE id = ?
                """, Long.class, recordId)).isGreaterThanOrEqualTo(25);
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.clear();
            assertThat(noShowRepository.findUnconfirmedBefore(
                    LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).minusHours(24)))
                    .extracting(RecruitmentNoShowRecordEntity::getId).contains(recordId);
        });
    }

    private void prepareCompetingGlobalSetting() {
        String suffix = Long.toUnsignedString(System.nanoTime(), 36);
        String slug = "cmp019-wave16-competitor-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, "CMP019 Wave16 competitor " + suffix, slug);
        competingTeamId = jdbcTemplate.queryForObject(
                "SELECT id FROM teams WHERE slug = ?", Long.class, slug);

        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(competingTeamId).build();
        setting.update(true, 1, 180, 30, PenaltyApplyScope.ALL_SCOPES, false, 30);
        competingSettingId = settingRepository.save(setting).getId();
    }

    private int countConfirmableNotifications() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM confirmable_notifications
                WHERE source_type = ? AND source_id = ?
                """, Integer.class, SOURCE_TYPE, penaltyId);
    }

    private int countConfirmableNotificationRecipients() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM confirmable_notification_recipients r
                JOIN confirmable_notifications c ON c.id = r.confirmable_notification_id
                WHERE c.source_type = ? AND c.source_id = ? AND r.user_id = ?
                """, Integer.class, SOURCE_TYPE, penaltyId, userId);
    }

    private int countDeliveredNotifications() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM notifications
                WHERE source_type = 'CONFIRMABLE_NOTIFICATION'
                  AND source_id IN (
                      SELECT id FROM confirmable_notifications WHERE source_type = ? AND source_id = ?)
                """, Integer.class, SOURCE_TYPE, penaltyId);
    }

    private int countConfirmableNotificationsForUser() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM confirmable_notifications c
                JOIN confirmable_notification_recipients r ON r.confirmable_notification_id = c.id
                WHERE c.source_type = ? AND r.user_id = ?
                """, Integer.class, SOURCE_TYPE, userId);
    }
}
