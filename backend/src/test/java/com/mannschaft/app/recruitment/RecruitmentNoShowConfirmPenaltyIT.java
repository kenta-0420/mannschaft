package com.mannschaft.app.recruitment;

import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import com.mannschaft.app.recruitment.service.RecruitmentNoShowConfirmBatch;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** NO_SHOW 確定から GLOBAL ペナルティ・確認通知までの MySQL トランザクション検証。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecruitmentNoShowConfirmPenaltyIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_TYPE = "RECRUITMENT_PENALTY";

    @Autowired private RecruitmentNoShowConfirmBatch batch;
    @Autowired private RecruitmentNoShowRecordRepository noShowRepository;
    @Autowired private RecruitmentListingRepository listingRepository;
    @Autowired private RecruitmentPenaltySettingRepository settingRepository;
    @Autowired private RecruitmentUserPenaltyRepository penaltyRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private Long userId;
    private Long teamId;
    private Long listingId;
    private Long participantId;
    private Long recordId;
    private Long settingId;
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
        if (settingId != null) settingRepository.deleteById(settingId);
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

        assertThat(noShowRepository.findById(recordId).orElseThrow().isConfirmed()).isTrue();
        List<RecruitmentUserPenaltyEntity> penalties = penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(penalties).hasSize(1);
        RecruitmentUserPenaltyEntity penalty = penalties.getFirst();
        penaltyId = penalty.getId();
        assertThat(penalty.getScopeType()).isEqualTo(RecruitmentScopeType.GLOBAL);
        assertThat(penalty.getScopeId()).isNull();
        assertThat(penaltyRepository.findApplicableActivePenaltyExpiry(
                userId, RecruitmentScopeType.ORGANIZATION, 999_999_999L, LocalDateTime.now()))
                .isEqualTo(penalty.getExpiresAt());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countConfirmableNotifications()).isEqualTo(1));
        transactionTemplate.executeWithoutResult(status -> target.confirmNoShows());
        assertThat(penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(1);
        assertThat(countConfirmableNotifications()).isEqualTo(1);
    }

    @Test
    void rollbackKeepsNoShowUnconfirmedAndCreatesNeitherPenaltyNorNotification() {
        prepareNoShow();
        RecruitmentNoShowConfirmBatch target = AopTestUtils.getUltimateTargetObject(batch);

        transactionTemplate.executeWithoutResult(status -> {
            target.confirmNoShows();
            status.setRollbackOnly();
        });

        assertThat(noShowRepository.findById(recordId).orElseThrow().isConfirmed()).isFalse();
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
                    1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                """, email);
        userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        String slug = "cmp019-wave16-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, NOW(), NOW())
                """, "CMP019 Wave16 " + suffix, slug);
        teamId = jdbcTemplate.queryForObject("SELECT id FROM teams WHERE slug = ?", Long.class, slug);

        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).build();
        setting.update(true, 1, 180, 30, PenaltyApplyScope.ALL_SCOPES, false, 30);
        settingId = settingRepository.save(setting).getId();

        LocalDateTime start = LocalDateTime.now().plusDays(10).withNano(0);
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
                    VALUES (:listingId, 'USER', :userId, :userId, 'CONFIRMED', NOW(), NOW())
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
        jdbcTemplate.update("UPDATE recruitment_no_show_records SET recorded_at = ? WHERE id = ?",
                LocalDateTime.now().minusHours(25), recordId);
    }

    private int countConfirmableNotifications() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM confirmable_notifications
                WHERE source_type = ? AND source_id = ?
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
