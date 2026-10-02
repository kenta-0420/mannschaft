package com.mannschaft.app.recruitment;

import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.service.RecruitmentNoShowService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave13 の異議申立理由を MySQL へコミットして再読込できることを確認する統合テスト。
 *
 * <p>単体テストのイベント発行確認や実機 E2E とは重複させず、Flyway で追加した
 * {@code recruitment_no_show_records.dispute_reason} の永続化境界だけを検証する。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Wave13 NO_SHOW 異議申立理由の MySQL 永続化")
class RecruitmentNoShowDisputeReasonPersistenceIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private RecruitmentNoShowService noShowService;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentNoShowRecordRepository noShowRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("異議申立理由をコミット後に MySQL の TEXT 列から再読込できる")
    void 異議申立理由をコミット後にMySqlから再読込できる() {
        String suffix = Long.toUnsignedString(System.nanoTime());
        Long userId = insertUser("cmp019-reason-" + suffix + "@example.com");
        Long listingId = insertListing(userId);
        Long participantId = insertParticipant(listingId, userId);
        Long recordId = transactionTemplate.execute(status -> noShowRepository.save(
                RecruitmentNoShowRecordEntity.builder()
                        .participantId(participantId)
                        .listingId(listingId)
                        .userId(userId)
                        .reason(NoShowReason.ADMIN_MARKED)
                        .recordedBy(userId)
                        .build()).getId());
        String disputeReason = "交通機関の遅延により到着できませんでした。";

        transactionTemplate.executeWithoutResult(status ->
                noShowService.dispute(recordId, userId, disputeReason));

        em.clear();
        RecruitmentNoShowRecordEntity reloaded = transactionTemplate.execute(status ->
                noShowRepository.findById(recordId).orElseThrow());
        String persistedReason = jdbcTemplate.queryForObject(
                "SELECT dispute_reason FROM recruitment_no_show_records WHERE id = ?",
                String.class, recordId);

        assertThat(reloaded.isDisputed()).isTrue();
        assertThat(reloaded.getDisputeReason()).isEqualTo(disputeReason);
        assertThat(persistedReason).isEqualTo(disputeReason);
    }

    private Long insertListing(Long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(10).withNano(0);
        return transactionTemplate.execute(status -> listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(899_000_000L + Math.floorMod(System.nanoTime(), 1_000_000L))
                .categoryId(1L)
                .title("CMP-019 異議申立理由永続化")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(createdBy)
                .build()).getId());
    }

    private Long insertParticipant(Long listingId, Long userId) {
        return transactionTemplate.execute(status -> {
            em.createNativeQuery("""
                    INSERT INTO recruitment_participants (
                        listing_id, participant_type, user_id, applied_by, status,
                        applied_at, status_changed_at)
                    VALUES (:listingId, 'USER', :userId, :userId, 'CONFIRMED', NOW(), NOW())
                    """)
                    .setParameter("listingId", listingId)
                    .setParameter("userId", userId)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("""
                    SELECT id FROM recruitment_participants
                    WHERE listing_id = :listingId AND user_id = :userId
                    """)
                    .setParameter("listingId", listingId)
                    .setParameter("userId", userId)
                    .getSingleResult()).longValue();
        });
    }

    private Long insertUser(String email) {
        return transactionTemplate.execute(status -> {
            em.createNativeQuery("""
                    INSERT INTO users (
                        email, last_name, first_name, display_name, status,
                        is_searchable, handle_searchable, contact_approval_required,
                        online_visibility, dm_receive_from, encryption_key_version,
                        locale, timezone, reporting_restricted, follow_list_visibility,
                        care_notification_enabled, offline_only, created_at, updated_at)
                    VALUES (:email, 'CMP019', 'IT', 'CMP019 IT', 'ACTIVE',
                        1, 1, 1, 'NOBODY', 'ANYONE', 1,
                        'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                    """)
                    .setParameter("email", email)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                    .setParameter("email", email)
                    .getSingleResult()).longValue();
        });
    }
}
