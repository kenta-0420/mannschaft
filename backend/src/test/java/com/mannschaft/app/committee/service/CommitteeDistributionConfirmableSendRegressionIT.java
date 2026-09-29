package com.mannschaft.app.committee.service;

import com.mannschaft.app.committee.dto.CommitteeDistributeRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-17 内部同期送信の回帰固定）。
 *
 * <p>軍議第8版確定稿 §1「内部から呼ぶ同期の {@code send(List<Long>)}（委員会伝達・協会請求・
 * 市の緊急休業・募集の自動取消など、受信者が少数で有界な呼び出し元）は現状維持とする。直接指定の
 * 認可は公開 API の入口で掛け、Service 全体には掛けない」を対象とする。</p>
 *
 * <p>本テストは {@code CommitteeDistributionService#distribute} 経由で
 * {@code ConfirmableNotificationService#send(List<Long>)}（同期・内部呼び出し経路）を実行し、
 * 新しいターゲット検証（{@code ConfirmableTargetAuthorizationValidator}）を一切通らずに
 * 受信者行が同期で作られることを固定する。<b>今の実装で green になることが期待どおりであり、
 * これは「既存経路が壊れていないこと」を確認する回帰テストである（redにする意図はない）。</b>
 * 出陣後もこのテストが green のままであることが、AC-17（内部同期経路は現状維持）の担保になる。</p>
 */
@DisplayName("CommitteeDistributionService 試練（AC-17 内部同期send経路の回帰・green固定）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CommitteeDistributionConfirmableSendRegressionIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CommitteeDistributionConfirmableSendRegressionIT.class);

    @Autowired
    private CommitteeDistributionService distributionService;
    @Autowired
    private CommitteeRepository committeeRepository;
    @Autowired
    private CommitteeMemberRepository committeeMemberRepository;
    @Autowired
    private ConfirmableNotificationRecipientRepository recipientRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long organizationId;
    private long chairUserId;
    private long member1UserId;
    private long member2UserId;
    private long committeeId;

    @BeforeEach
    void setUp() {
        organizationId = createOrg();
        chairUserId = insertUser(9_301_001L);
        member1UserId = insertUser(9_301_002L);
        member2UserId = insertUser(9_301_003L);

        CommitteeEntity committee = committeeRepository.save(CommitteeEntity.builder()
                .organizationId(organizationId)
                .name("AC-17回帰用委員会")
                .status(CommitteeStatus.ACTIVE)
                .build());
        committeeId = committee.getId();

        committeeMemberRepository.save(CommitteeMemberEntity.builder()
                .committeeId(committeeId)
                .userId(chairUserId)
                .role(CommitteeRole.CHAIR)
                .joinedAt(LocalDateTime.now().minusDays(10))
                .build());
        committeeMemberRepository.save(CommitteeMemberEntity.builder()
                .committeeId(committeeId)
                .userId(member1UserId)
                .role(CommitteeRole.MEMBER)
                .joinedAt(LocalDateTime.now().minusDays(5))
                .build());
        committeeMemberRepository.save(CommitteeMemberEntity.builder()
                .committeeId(committeeId)
                .userId(member2UserId)
                .role(CommitteeRole.MEMBER)
                .joinedAt(LocalDateTime.now().minusDays(5))
                .build());
    }

    @Test
    @DisplayName("AC-17: 委員会伝達（内部同期send）は新しいターゲット検証を通らずに受信者行を同期で作る")
    void ac17_committeeDistributionSyncSendBypassesTargetValidation() throws Exception {
        CommitteeDistributeRequest request = buildRequest(
                "CUSTOM_MESSAGE", null,
                "AC-17回帰テスト伝達", "本文",
                DistributionScope.COMMITTEE_ONLY, Boolean.FALSE, ConfirmationMode.REQUIRED,
                LocalDateTime.now().plusDays(7));

        var log_ = distributionService.distribute(committeeId, request, chairUserId);

        assertThat(log_.getConfirmableNotificationId())
                .as("AC-17: 委員会伝達は確認通知を同期で生成する")
                .isNotNull();

        List<ConfirmableNotificationRecipientEntity> recipients =
                recipientRepository.findByConfirmableNotificationId(log_.getConfirmableNotificationId());
        log.info("[AC-17] recipients={}", recipients.size());
        assertThat(recipients)
                .as("AC-17: 現役の委員会メンバー（CHAIR自身を含む・除外は公開APIのみ）全員に受信者行が同期で作られる")
                .hasSize(3);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private CommitteeDistributeRequest buildRequest(
            String contentType, Long contentId, String customTitle, String customBody,
            DistributionScope targetScope, Boolean announcementEnabled, ConfirmationMode confirmationMode,
            LocalDateTime confirmationDeadlineAt) throws ReflectiveOperationException {
        CommitteeDistributeRequest req = new CommitteeDistributeRequest();
        setField(req, "contentType", contentType);
        setField(req, "contentId", contentId);
        setField(req, "customTitle", customTitle);
        setField(req, "customBody", customBody);
        setField(req, "targetScope", targetScope);
        setField(req, "announcementEnabled", announcementEnabled);
        setField(req, "confirmationMode", confirmationMode);
        setField(req, "confirmationDeadlineAt", confirmationDeadlineAt);
        return req;
    }

    private void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = CommitteeDistributeRequest.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private long createOrg() {
        jdbc.update("INSERT INTO organizations "
                        + "(slug, name, org_type, visibility, hierarchy_visibility, supporter_enabled, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, 'AC-17回帰用組織', 'COMMUNITY', 'PUBLIC', 'FULL', 1, 0, NOW(), NOW())",
                // slug は VARCHAR(30)。"committee-ac17-" (15) + nanoTime下位9桁 で30文字以内に収める。
                "committee-ac17-" + (System.nanoTime() % 1_000_000_000L));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private long insertUser(long userId) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO users ("
                        + "id, email, last_name, first_name, display_name, status, created_at, updated_at, "
                        + "handle_searchable, contact_approval_required, online_visibility, is_searchable, dm_receive_from, "
                        + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only"
                        + ") VALUES ("
                        + "?, ?, 'L', 'F', ?, 'ACTIVE', ?, ?, "
                        + "1, 1, 'NOBODY', 1, 'ANYONE', "
                        + "1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0)",
                userId, "committee-ac17-it-" + userId + "@example.test", "U" + userId, now, now);
        return userId;
    }
}
