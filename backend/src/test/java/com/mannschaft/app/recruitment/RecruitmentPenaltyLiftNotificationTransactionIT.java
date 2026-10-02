package com.mannschaft.app.recruitment;

import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyLiftBatch;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** AUTO_EXPIRED の確定後だけ実MySQLへ本人通知を記録するトランザクション境界テスト。 */
class RecruitmentPenaltyLiftNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final String SOURCE_TYPE = "RECRUITMENT_PENALTY";

    @Autowired
    private RecruitmentPenaltyLiftBatch batch;

    @Autowired
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Autowired
    private RecruitmentPenaltySettingRepository settingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private Long penaltyId;
    private Long settingId;
    private Long teamId;

    @AfterEach
    void cleanup() {
        if (penaltyId != null) {
            jdbcTemplate.update("DELETE FROM notifications WHERE source_type = ? AND source_id = ?", SOURCE_TYPE, penaltyId);
            penaltyRepository.deleteById(penaltyId);
        }
        if (settingId != null) {
            settingRepository.deleteById(settingId);
        }
        if (teamId != null) {
            jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void committedAutoExpiryLiftsPenaltyAndCreatesOneNormalNotification() {
        userId = createUser();
        penaltyId = createExpiredPenalty(userId);

        // 分散ロックの lockAtLeastFor=5m が別テストへ波及しないよう、
        // 対象メソッドを明示トランザクション内で実行して通知境界を検証する。
        RecruitmentPenaltyLiftBatch target = AopTestUtils.getUltimateTargetObject(batch);
        transactionTemplate.executeWithoutResult(status -> target.liftExpiredPenalties());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.countBySourceTypeAndSourceId(SOURCE_TYPE, penaltyId)).isEqualTo(1));
        RecruitmentUserPenaltyEntity penalty = penaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(penalty.getLiftReason()).isEqualTo(PenaltyLiftReason.AUTO_EXPIRED);
        assertThat(penalty.getLiftedAt()).isNotNull();
    }

    @Test
    void rolledBackAutoExpiryDoesNotCreateNotification() {
        userId = createUser();
        penaltyId = createExpiredPenalty(userId);

        RecruitmentPenaltyLiftBatch target = AopTestUtils.getUltimateTargetObject(batch);
        transactionTemplate.executeWithoutResult(status -> {
            target.liftExpiredPenalties();
            status.setRollbackOnly();
        });

        assertThat(notificationRepository.countBySourceTypeAndSourceId(SOURCE_TYPE, penaltyId)).isZero();
        assertThat(penaltyRepository.findById(penaltyId).orElseThrow().getLiftedAt()).isNull();
    }

    private Long createExpiredPenalty(Long targetUserId) {
        LocalDateTime now = LocalDateTime.now();
        teamId = createTeam();
        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).build();
        setting.update(true, 3, 180, 30, PenaltyApplyScope.THIS_SCOPE_ONLY, false, 30);
        settingId = settingRepository.save(setting).getId();
        return penaltyRepository.save(RecruitmentUserPenaltyEntity.builder()
                .userId(targetUserId)
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .triggeredBySettingId(settingId)
                .triggeredNoShowCount(3)
                .startedAt(now.minusDays(8))
                .expiresAt(now.minusDays(1))
                .build()).getId();
    }

    private Long createUser() {
        String email = "cmp019-wave14-" + System.nanoTime() + "@example.test";
        jdbcTemplate.update("""
                INSERT INTO users (email, last_name, first_name, display_name, status,
                    is_searchable, handle_searchable, contact_approval_required, online_visibility,
                    dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted,
                    follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, 'CMP019', 'Wave14', 'CMP019 Wave14', 'ACTIVE',
                    1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())
                """, email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long createTeam() {
        String name = "CMP019 Wave14 " + System.nanoTime();
        String slug = "cmp019-wave14-" + Long.toUnsignedString(System.nanoTime(), 36);
        jdbcTemplate.update("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, NOW(), NOW())
                """, name, slug);
        return jdbcTemplate.queryForObject("SELECT id FROM teams WHERE slug = ?", Long.class, slug);
    }
}
