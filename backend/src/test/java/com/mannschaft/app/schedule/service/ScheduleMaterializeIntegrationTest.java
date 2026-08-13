package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ReminderKind;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceReminderEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleScheduledTaskEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.ScheduleScheduledTaskRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 機能55「時刻起動バッチ materialize」実機統合テスト。
 *
 * <h2>検証範囲</h2>
 * <ol>
 *   <li>予約アンケート materialize — PENDING タスク(scheduledAt=過去)→ runBatch() →
 *       タスク CREATED・materialized_entity_id セット・PUBLISHED Survey 存在</li>
 *   <li>予約出欠 materialize — scheduledAt=過去 → runBatch() →
 *       タスク CREATED・schedule_attendances メンバー分生成</li>
 *   <li>即時出欠の配線根治 — attendanceRequired=true・予約タスク無し →
 *       {@link ScheduleAttendanceSolicitationEventListener#onScheduleCreated} を直呼びで検証</li>
 *   <li>共有リマインダー発火根治 — is_sent=false・実効時刻到来 → runBatch() → is_sent=true</li>
 *   <li>個人リマインダー発火根治 — notified=false・due到来 → runBatch() → notified=true</li>
 *   <li>予約タスク取消 — 未来 scheduledAt の PENDING タスクをキャンセル → batch でも CREATED されない</li>
 *   <li>失敗リトライ — 不正 payload → attempt_count 加算・MAX_ATTEMPTS 到達で FAILED 確定</li>
 * </ol>
 *
 * <h2>設計方針</h2>
 * <ul>
 *   <li>time-based バッチをテストするため scheduledAt/remindAt を「過去」に設定し、時刻待ちを一切しない</li>
 *   <li>ShedLock は test プロファイルで無効のため、バッチ Bean を直接 @Autowire して呼び出す</li>
 *   <li>FK 無し（クロスドメイン論理参照）のため users/teams/organizations は原則不要。
 *       ただし {@code openAttendanceSolicitation} → {@code UserRoleRepository.findUserIdsByScope} →
 *       {@code JOIN users WHERE status='ACTIVE'} が実行されるため、通知確認テストでは users/user_roles を seed する</li>
 *   <li>通知（NotificationService）は実 DB に保存されるが、本テストでは生成されたリマインダー/出欠レコードの
 *       状態フラグ（is_sent / notified）を観測することで「通知発火」を間接確認する
 *       （PUSH dispatch は外部依存のため検証対象外）</li>
 *   <li>{@link AbstractMySqlIntegrationTest} を継承し MySQLコンテナ・ApplicationContext を共有
 *       （OOM 防止パターン。Docker 未起動環境では @EnabledIf により全テスト skip）</li>
 * </ul>
 *
 * <h2>トランザクション設計（重要）</h2>
 * <p>{@code ScheduleScheduledTaskBatchService.materializeOne}/{@code recordFailure} は
 * {@code @Transactional(propagation=REQUIRES_NEW)} で独立トランザクションとして動く。
 * テスト全体を {@code @Transactional} でラップすると、セットアップデータが未コミットのまま
 * 別トランザクションから不可視になり、runBatch() が何も拾えず PENDING のまま残る。</p>
 * <p>このため本テストは <b>クラスレベルの {@code @Transactional} を使用しない</b>。
 * セットアップデータは {@link TransactionTemplate} で確実にコミットし、
 * テスト後は {@code @AfterEach} で対象テーブルを明示クリーンアップする。
 * 手本: {@code PropertyWorkPackageEventListenerIntegrationTest}。</p>
 *
 * <p><b>回帰防止コメント (リマインダー)</b>: 機能55 第一陣までリマインダーバッチが存在しなかったため、
 * 共有/個人どちらのリマインダーも永遠に発火しなかった。本テストがその根治（第二陣）を確認する。</p>
 */
@DisplayName("機能55 予約 materialize・リマインダー統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleMaterializeIntegrationTest extends AbstractMySqlIntegrationTest {

    // --- batch services (ShedLock 無効 @test profile → autowire 直呼び可) ---
    @Autowired
    private ScheduleScheduledTaskBatchService scheduledTaskBatchService;

    @Autowired
    private ScheduleReminderBatchService scheduleReminderBatchService;

    @Autowired
    private PersonalScheduleReminderBatchService personalScheduleReminderBatchService;

    // --- repositories ---
    @Autowired
    private ScheduleScheduledTaskRepository scheduledTaskRepository;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleAttendanceRepository attendanceRepository;

    @Autowired
    private ScheduleAttendanceReminderRepository reminderRepository;

    @Autowired
    private PersonalScheduleReminderRepository personalReminderRepository;

    @Autowired
    private SurveyRepository surveyRepository;

    @Autowired
    private ScheduleAttendanceSolicitationEventListener solicitationEventListener;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private PlatformTransactionManager txManager;

    // --- テスト用定数（FK 無しのためスキーマに存在しない ID で可） ---
    private static final Long ORG_ID       = 8_801L;
    private static final Long TEAM_ID      = 8_802L;
    private static final Long SCHEDULE_ID  = 8_803L;
    private static final Long CREATED_BY   = 8_804L;

    // 通知発火テスト用ユーザー（users/user_roles テーブルに実際に insert する）
    private Long notifyMember1;
    private Long notifyMember2;
    private Long memberRoleId;

    /**
     * テスト間汚染防止のため作成したエンティティの ID を追跡し @AfterEach で削除する。
     * REQUIRES_NEW バッチが生成したレコード（schedule_attendances, surveys 等）も含め、
     * 固有の schedule_id / org_id で絞り込んで削除する。
     *
     * schedule_scheduled_tasks: organization_id=ORG_ID で一括削除（UUID binary 変換不要）
     * schedule_attendance_reminders / personal_schedule_reminders: Long 主キー（BIGINT AUTO_INCREMENT）
     * schedules: Long 主キー（BIGINT AUTO_INCREMENT）
     */
    private final List<Long> createdScheduleIds = new ArrayList<>();
    private final List<Long> createdReminderIds = new ArrayList<>();
    private final List<Long> createdPersonalReminderIds = new ArrayList<>();

    // ========================================================================
    // クリーンアップ
    // ========================================================================

    @AfterEach
    void cleanUp() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // schedule_scheduled_tasks:
            // organization_id と scope_id（TEAM_ID）でテスト固有行を一括削除。
            // @SQLRestriction を回避するため JPA ではなくネイティブクエリで物理削除する。
            em.createNativeQuery(
                    "DELETE FROM schedule_scheduled_tasks WHERE organization_id = :oid AND deleted_at IS NULL")
                    .setParameter("oid", ORG_ID).executeUpdate();
            // また SCHEDULE_ID+100 / +200 / +300 / +400 で作った行も organization_id=ORG_ID なので同一の条件で削除済み
            // schedule_attendances（schedule_id で絞り込み）
            for (Long sid : createdScheduleIds) {
                em.createNativeQuery("DELETE FROM schedule_attendances WHERE schedule_id = :sid")
                        .setParameter("sid", sid).executeUpdate();
            }
            // schedule_attendance_reminders（Long 主キー）
            for (Long id : createdReminderIds) {
                em.createNativeQuery("DELETE FROM schedule_attendance_reminders WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            // personal_schedule_reminders（Long 主キー）
            for (Long id : createdPersonalReminderIds) {
                em.createNativeQuery("DELETE FROM personal_schedule_reminders WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            // schedules
            for (Long sid : createdScheduleIds) {
                em.createNativeQuery("DELETE FROM schedules WHERE id = :sid")
                        .setParameter("sid", sid).executeUpdate();
            }
            // surveys（scope_type=TEAM, scope_id=TEAM_ID で絞り込み）
            em.createNativeQuery(
                    "DELETE FROM surveys WHERE scope_type = 'TEAM' AND scope_id = :tid")
                    .setParameter("tid", TEAM_ID).executeUpdate();
            // user_roles（team_id=TEAM_ID で絞り込み）
            em.createNativeQuery(
                    "DELETE FROM user_roles WHERE team_id = :tid")
                    .setParameter("tid", TEAM_ID).executeUpdate();
            // users（テスト固有メール）
            em.createNativeQuery(
                    "DELETE FROM users WHERE email LIKE 'matit55.%@example.com'")
                    .executeUpdate();
            return null;
        });
        createdScheduleIds.clear();
        createdReminderIds.clear();
        createdPersonalReminderIds.clear();
        notifyMember1 = null;
        notifyMember2 = null;
        memberRoleId = null;
    }

    // ========================================================================
    // ヘルパー（全て TransactionTemplate で確実にコミット）
    // ========================================================================

    /**
     * ScheduleScheduledTaskEntity をPENDING で保存し、確実にコミットして返す。
     * REQUIRES_NEW バッチが同レコードを別 Tx から読めるよう、ここで commit する。
     * クリーンアップは @AfterEach で organization_id=ORG_ID の一括削除で行う。
     */
    private ScheduleScheduledTaskEntity persistTask(
            Long scheduleId, ScheduledTaskType taskType,
            LocalDateTime scheduledAt, String payloadJson) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        UUID taskId = tx.execute(status -> {
            ScheduleScheduledTaskEntity task = ScheduleScheduledTaskEntity.builder()
                    .scheduleId(scheduleId)
                    .organizationId(ORG_ID)
                    .scopeType(CalendarSyncScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .taskType(taskType)
                    .scheduledAt(scheduledAt)
                    .status(ScheduledTaskStatus.PENDING)
                    .payloadJson(payloadJson)
                    .createdBy(CREATED_BY)
                    .build();
            em.persist(task);
            em.flush();
            return task.getId();
        });
        return scheduledTaskRepository.findById(taskId).orElseThrow();
    }

    /** schedule_attendance_reminders に is_sent=false で保存し確実にコミットする（ABSOLUTE 指定） */
    private ScheduleAttendanceReminderEntity persistReminder(
            Long scheduleId, LocalDateTime remindAt) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long remId = tx.execute(status -> {
            ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(ReminderKind.ABSOLUTE)
                    .remindAt(remindAt)
                    .build();
            em.persist(reminder);
            em.flush();
            return reminder.getId();
        });
        createdReminderIds.add(remId);
        return reminderRepository.findById(remId).orElseThrow();
    }

    /** schedule_attendance_reminders に is_sent=false で保存し確実にコミットする（RELATIVE 指定） */
    private ScheduleAttendanceReminderEntity persistRelativeReminder(
            Long scheduleId, int remindBeforeMinutes) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long remId = tx.execute(status -> {
            ScheduleAttendanceReminderEntity reminder = ScheduleAttendanceReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(ReminderKind.RELATIVE)
                    .remindBeforeMinutes(remindBeforeMinutes)
                    .build();
            em.persist(reminder);
            em.flush();
            return reminder.getId();
        });
        createdReminderIds.add(remId);
        return reminderRepository.findById(remId).orElseThrow();
    }

    /** personal_schedule_reminders に notified=false で保存し確実にコミットする（ABSOLUTE） */
    private PersonalScheduleReminderEntity persistPersonalReminderAbsolute(
            Long scheduleId, LocalDateTime remindAt) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long remId = tx.execute(status -> {
            PersonalScheduleReminderEntity reminder = PersonalScheduleReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(ReminderKind.ABSOLUTE)
                    .remindAt(remindAt)
                    .build();
            em.persist(reminder);
            em.flush();
            return reminder.getId();
        });
        createdPersonalReminderIds.add(remId);
        return personalReminderRepository.findById(remId).orElseThrow();
    }

    /** personal_schedule_reminders に notified=false で保存し確実にコミットする（RELATIVE） */
    private PersonalScheduleReminderEntity persistPersonalReminderRelative(
            Long scheduleId, int remindBeforeMinutes) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long remId = tx.execute(status -> {
            PersonalScheduleReminderEntity reminder = PersonalScheduleReminderEntity.builder()
                    .scheduleId(scheduleId)
                    .reminderKind(ReminderKind.RELATIVE)
                    .remindBeforeMinutes(remindBeforeMinutes)
                    .build();
            em.persist(reminder);
            em.flush();
            return reminder.getId();
        });
        createdPersonalReminderIds.add(remId);
        return personalReminderRepository.findById(remId).orElseThrow();
    }

    /**
     * schedules テーブルに最小限の行を確実にコミットして返す。
     * attendanceRequired / attendanceDeadline は引数で制御。
     */
    private ScheduleEntity persistSchedule(Long teamId, Long userId,
                                            boolean attendanceRequired,
                                            LocalDateTime startAt) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long scheduleId = tx.execute(status -> {
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(teamId)
                    .userId(userId)
                    .title("統合テスト用予定")
                    .startAt(startAt)
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(attendanceRequired)
                    .createdBy(CREATED_BY)
                    .build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
        createdScheduleIds.add(scheduleId);
        return scheduleRepository.findById(scheduleId).orElseThrow();
    }

    /**
     * schedules テーブルに「出欠設定つき」の行を確実にコミットして返す（Issue #2508 欠陥B 検証用）。
     *
     * <p>materialize 前の初期値を明示的に与え、payload_json の設定が実際に適用されたか
     * （あるいは未指定時に初期値が保たれるか）を判別できるようにする。</p>
     */
    private ScheduleEntity persistScheduleWithAttendanceSettings(
            Long teamId, LocalDateTime startAt,
            LocalDateTime attendanceDeadline,
            CommentOption commentOption,
            MinResponseRole minResponseRole) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Long scheduleId = tx.execute(status -> {
            ScheduleEntity schedule = ScheduleEntity.builder()
                    .teamId(teamId)
                    .title("統合テスト用予定（出欠設定つき）")
                    .startAt(startAt)
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ANYONE)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .attendanceDeadline(attendanceDeadline)
                    .commentOption(commentOption)
                    .minResponseRole(minResponseRole)
                    .createdBy(CREATED_BY)
                    .build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
        createdScheduleIds.add(scheduleId);
        return scheduleRepository.findById(scheduleId).orElseThrow();
    }

    /** 通知発火テスト用のユーザー・ロール・user_roles seed（確実にコミット） */
    private void seedUsersAndRoles() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            // roles テーブルに MEMBER ロールを挿入（既存の場合は重複 INSERT を無視）
            em.createNativeQuery(
                    "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                            + "VALUES ('MEMBER', 'メンバー', 4, 0, NOW(), NOW())")
                    .executeUpdate();
            em.flush();

            memberRoleId = ((Number) em.createNativeQuery(
                    "SELECT id FROM roles WHERE name = 'MEMBER'").getSingleResult()).longValue();

            notifyMember1 = insertUser("matit55.member1@example.com", "予約", "太郎");
            notifyMember2 = insertUser("matit55.member2@example.com", "予約", "花子");

            // user_roles: 両ユーザーを TEAM_ID メンバーとして登録
            insertUserRole(notifyMember1, memberRoleId, TEAM_ID, null);
            insertUserRole(notifyMember2, memberRoleId, TEAM_ID, null);
            em.flush();
            return null;
        });
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT IGNORE INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleId, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT IGNORE INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleId)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    // ========================================================================
    // テスト 1: 予約アンケート materialize
    // ========================================================================

    @Nested
    @DisplayName("テスト1: 予約アンケート materialize")
    class SurveyMaterialize {

        @Test
        @DisplayName("scheduledAt=過去の SURVEY タスク → runBatch() → タスク CREATED・Survey PUBLISHED")
        void survey_runBatch_createsPublishedSurvey() throws Exception {
            // Arrange: survey payload を JSON 化（survey ドメインの CreateSurveyRequest）
            String surveyPayload = objectMapper.writeValueAsString(
                    new com.mannschaft.app.survey.dto.CreateSurveyRequest(
                            "機能55 統合テスト用アンケート",   // title
                            "統合テスト説明",                // description
                            false,                          // isAnonymous
                            false,                          // allowMultipleSubmissions
                            com.mannschaft.app.survey.ResultsVisibility.AFTER_RESPONSE,               // resultsVisibility (ResultsVisibility enum)
                            com.mannschaft.app.survey.DistributionMode.ALL,                          // distributionMode (DistributionMode enum)
                            com.mannschaft.app.survey.UnrespondedVisibility.CREATOR_AND_ADMIN,            // unrespondedVisibility
                            false,                          // autoPostToTimeline
                            null,                           // seriesId
                            null,                           // remindBeforeHours
                            null,                           // startsAt
                            null,                           // expiresAt
                            List.of(                        // questions (最低1件必要)
                                    new com.mannschaft.app.survey.dto.CreateQuestionRequest(
                                            com.mannschaft.app.survey.QuestionType.SINGLE_CHOICE,
                                            "参加しますか？",
                                            true, 0, null, null, null, null, null,
                                            List.of(
                                                    new com.mannschaft.app.survey.dto.CreateOptionRequest("参加", 0),
                                                    new com.mannschaft.app.survey.dto.CreateOptionRequest("不参加", 1)))),
                            null,                           // targetUserIds
                            null,                           // resultViewerUserIds
                            false,                          // includeSupporters
                            false                           // teamBreakdownEnabled
                    ));

            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(5);
            // persistTask は TransactionTemplate で確実にコミットする
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID, ScheduledTaskType.SURVEY, pastTime, surveyPayload);
            UUID taskId = task.getId();

            // Act: コミット済みデータを REQUIRES_NEW バッチが読める
            scheduledTaskBatchService.runBatch();

            // Assert: タスクが CREATED になりmaterialized_entity_id がセットされている
            ScheduleScheduledTaskEntity afterTask = scheduledTaskRepository.findById(taskId).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("予約アンケートmaterialize後はCREATEDになること")
                    .isEqualTo(ScheduledTaskStatus.CREATED);
            assertThat(afterTask.getMaterializedEntityId())
                    .as("materialized_entity_id が Survey id にセットされること")
                    .isNotNull();

            // Assert: 対象スコープに status=PUBLISHED の Survey が存在する
            long publishedCount = surveyRepository.countByScopeTypeAndScopeIdAndStatus(
                    "TEAM", TEAM_ID, SurveyStatus.PUBLISHED);
            assertThat(publishedCount)
                    .as("対象スコープに PUBLISHED Survey が生成されること")
                    .isGreaterThanOrEqualTo(1L);
        }

        /**
         * {@code startsAt}/{@code expiresAt} を raw な生 JSON 文字列で差し替えた survey payload を組み立てる
         * （Issue #2508 AC-11）。
         *
         * <p>{@link com.mannschaft.app.survey.dto.CreateSurveyRequest} を一旦 {@code startsAt}/{@code expiresAt}
         * を null にして直列化し、その後 {@link ObjectNode} でこの2フィールドだけを raw 文字列に差し替える。
         * こうすることで他のフィールドの整合性を保ったまま、{@code payload_json} に「その時代に書かれた生の JSON」
         * （テスト2-B {@code rawAttendancePayload} と同じ狙い）を再現できる。</p>
         */
        private String surveyPayloadWithRawDates(String startsAtRaw, String expiresAtRaw) throws Exception {
            String base = objectMapper.writeValueAsString(
                    new com.mannschaft.app.survey.dto.CreateSurveyRequest(
                            "機能55 AC-11 統合テスト用アンケート",   // title
                            "startsAt/expiresAt 往復検証",         // description
                            false,                                // isAnonymous
                            false,                                // allowMultipleSubmissions
                            com.mannschaft.app.survey.ResultsVisibility.AFTER_RESPONSE,                     // resultsVisibility
                            com.mannschaft.app.survey.DistributionMode.ALL,                                // distributionMode
                            com.mannschaft.app.survey.UnrespondedVisibility.CREATOR_AND_ADMIN,                  // unrespondedVisibility
                            false,                                // autoPostToTimeline
                            null,                                 // seriesId
                            null,                                 // remindBeforeHours
                            null,                                 // startsAt（後で raw 差し替え）
                            null,                                 // expiresAt（後で raw 差し替え）
                            List.of(
                                    new com.mannschaft.app.survey.dto.CreateQuestionRequest(
                                            com.mannschaft.app.survey.QuestionType.SINGLE_CHOICE,
                                            "参加しますか？",
                                            true, 0, null, null, null, null, null,
                                            List.of(
                                                    new com.mannschaft.app.survey.dto.CreateOptionRequest("参加", 0),
                                                    new com.mannschaft.app.survey.dto.CreateOptionRequest("不参加", 1)))),
                            null,                                 // targetUserIds
                            null,                                 // resultViewerUserIds
                            false,                                // includeSupporters
                            false                                 // teamBreakdownEnabled
                    ));
            ObjectNode node = (ObjectNode) objectMapper.readTree(base);
            node.put("startsAt", startsAtRaw);
            node.put("expiresAt", expiresAtRaw);
            return objectMapper.writeValueAsString(node);
        }

        /**
         * <b>AC-11（Issue #2508 往復IT・試練の穴）</b>: 内部監査により、{@code ScheduleScheduledTaskBatchService}
         * の {@code materializeOne} が {@code @Primary ObjectMapper} で {@code payload_json} を素の
         * {@link com.mannschaft.app.survey.dto.CreateSurveyRequest}（{@code startsAt}/{@code expiresAt} が素の
         * {@link LocalDateTime}）へ読み戻す唯一の経路であることが判明した。書き込みは予定作成者の HTTP スレッド
         * （TimezoneContextHolder 解決済み → ユーザー TZ のオフセット付きで書く）、読み戻しはバッチスレッド
         * （フィルターを通らず未解決）という非対称構造のため、<b>是正前は標準 LocalDateTime デシリアライザが
         * オフセット付き文字列を拒否し、{@code materializeOne} が例外を投げて予約タスクが FAILED になっていた</b>
         * （JST ユーザーでも {@code +09:00} が付くため全ユーザーで壊れていた疑いが濃い）。
         *
         * <p>既存の {@code survey_runBatch_createsPublishedSurvey} は {@code startsAt=null, expiresAt=null}
         * を明示的に渡しており、この欠陥の核心である非 null {@code startsAt}/{@code expiresAt} の実往復を
         * 一切検証していなかった（試練の穴）。本テストはその穴を埋める。</p>
         *
         * <p>本テストは「オフセット付きで書かれた payload_json が現在は正しく読める」ことを固定するため、
         * <b>是正前後の挙動差分の直接証明</b>にもなる（是正前ならここで {@code InvalidFormatException} が
         * 伝播し {@code materializeOne} が例外終了・タスクが FAILED になっていたはず）。</p>
         */
        @Test
        @DisplayName("AC-11: 非JST（LA -07:00）オフセット付き startsAt/expiresAt の payload_json → "
                + "materialize で瞬間保存されJST壁時計に正規化される（是正前は標準デシリアライザが拒否しFAILEDになっていた欠陥の回帰ガード）")
        void 非JSTオフセット付きstartsAtExpiresAtがmaterializeで正しくJSTへ正規化される() throws Exception {
            // Arrange: America/Los_Angeles ユーザーが作成した想定（サーバーは常にオフセット付きで書く実装のため、
            // 固定オフセット -07:00 のリテラルで payload_json を組み立てる）。
            String startsAtRaw = "2030-03-10T09:00:00-07:00";
            String expiresAtRaw = "2030-03-17T09:00:00-07:00";
            LocalDateTime expectedStartsAtJst = OffsetDateTime.parse(startsAtRaw)
                    .atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();
            LocalDateTime expectedExpiresAtJst = OffsetDateTime.parse(expiresAtRaw)
                    .atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

            String payload = surveyPayloadWithRawDates(startsAtRaw, expiresAtRaw);
            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(4);
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 500, ScheduledTaskType.SURVEY, pastTime, payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert: materialize 成功（FAILED にならないこと自体が「是正の証明」）
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("AC-11: 非JSTオフセット付きpayloadでもmaterialize成功しFAILEDにならないこと（attempt=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);
            assertThat(afterTask.getMaterializedEntityId())
                    .as("materialized_entity_id が Survey id にセットされること")
                    .isNotNull();

            // Assert: Survey本体の startsAt/expiresAt が「読み戻した瞬間」としてJST壁時計に正規化されていること
            SurveyEntity survey = surveyRepository.findById(afterTask.getMaterializedEntityId()).orElseThrow();
            assertThat(survey.getStartsAt())
                    .as("AC-11: LA -07:00 で書かれた startsAt が同一瞬間のJST壁時計として保存されること")
                    .isEqualTo(expectedStartsAtJst);
            assertThat(survey.getExpiresAt())
                    .as("AC-11: LA -07:00 で書かれた expiresAt が同一瞬間のJST壁時計として保存されること")
                    .isEqualTo(expectedExpiresAtJst);
        }

        /**
         * <b>回帰ガード</b>: JST（{@code +09:00}）ユーザーが作成した payload でも、AC-11 是正後に
         * 恒等変換（既存挙動と完全一致）となることを固定する。{@code users.timezone} は
         * {@code NOT NULL DEFAULT 'Asia/Tokyo'} のため、国内ユーザーの往復は本テストが基準となる。
         */
        @Test
        @DisplayName("AC-11回帰: JST(+09:00)オフセット付き startsAt/expiresAt は恒等変換のまま既存挙動を維持する")
        void JSTオフセット付きstartsAtExpiresAtは恒等変換のまま保存される() throws Exception {
            // Arrange
            String startsAtRaw = "2030-04-01T10:00:00+09:00";
            String expiresAtRaw = "2030-04-08T10:00:00+09:00";
            LocalDateTime expectedStartsAtJst = LocalDateTime.of(2030, 4, 1, 10, 0, 0);
            LocalDateTime expectedExpiresAtJst = LocalDateTime.of(2030, 4, 8, 10, 0, 0);

            String payload = surveyPayloadWithRawDates(startsAtRaw, expiresAtRaw);
            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(3);
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 600, ScheduledTaskType.SURVEY, pastTime, payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("AC-11回帰: JSTオフセット付きpayloadでもmaterialize成功すること（attempt=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);

            SurveyEntity survey = surveyRepository.findById(afterTask.getMaterializedEntityId()).orElseThrow();
            assertThat(survey.getStartsAt())
                    .as("AC-11回帰: JSTユーザーのstartsAtは恒等変換のまま保存されること")
                    .isEqualTo(expectedStartsAtJst);
            assertThat(survey.getExpiresAt())
                    .as("AC-11回帰: JSTユーザーのexpiresAtは恒等変換のまま保存されること")
                    .isEqualTo(expectedExpiresAtJst);
        }
    }

    // ========================================================================
    // テスト 2: 予約出欠 materialize
    // ========================================================================

    @Nested
    @DisplayName("テスト2: 予約出欠 materialize")
    class AttendanceMaterialize {

        @Test
        @DisplayName("scheduledAt=過去の ATTENDANCE タスク → materializeOne() → タスク CREATED・出欠レコード生成")
        void attendance_runBatch_opensAttendanceSolicitation() throws Exception {
            // Arrange: users/user_roles を seed してメンバーを確定（コミット済み）
            seedUsersAndRoles();

            // schedule を作成（attendanceRequired=true, チームスコープ）
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, true,
                    LocalDateTime.now().plusHours(2));

            // ATTENDANCE 予約タスクを past に設定
            String attendancePayload = objectMapper.writeValueAsString(
                    new ScheduleScheduledTaskService.AttendancePayload(null, null, null));
            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(3);
            ScheduleScheduledTaskEntity task = persistTask(
                    schedule.getId(), ScheduledTaskType.ATTENDANCE, pastTime, attendancePayload);
            UUID taskId = task.getId();

            // Act: プロキシ経由で materializeOne を直接呼ぶ（REQUIRES_NEW Tx が正しく機能する）。
            // runBatch() は @Scheduled バッチが自動起動しているとタスクが CREATED 済みで
            // due.isEmpty() early-return する競合リスクがあるため、materializeOne を直接呼ぶ方式を採用。
            // materializeOne が @Transactional(REQUIRES_NEW) を持つため、Spring プロキシ経由での
            // 呼び出しで独立トランザクションが保証される（self-invocation を使わず、@Autowired 経由）。
            scheduledTaskBatchService.materializeOne(task);

            // Assert: タスクが CREATED になること
            ScheduleScheduledTaskEntity afterTask = scheduledTaskRepository.findById(taskId).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("予約出欠materialize後はCREATEDになること（attempt_count=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);

            // Assert: 出欠レコードがメンバー分生成されていること（seedで2名）
            long attendanceCount = attendanceRepository.countByScheduleId(schedule.getId());
            assertThat(attendanceCount)
                    .as("チームメンバー2名分の出欠レコードが生成されること")
                    .isEqualTo(2L);
        }
    }

    // ========================================================================
    // テスト 2-B: 予約出欠の設定適用（Issue #2508 欠陥B）
    // ========================================================================

    /**
     * <b>回帰防止（欠陥B）</b>: materialize 時に {@code payload_json} が一度も読まれず
     * {@code openAttendanceSolicitation(scheduleId)} を呼ぶだけだったため、ユーザーが指定した
     * 回答締切・コメント設定・最低応答ロールが「書かれるだけで一切適用されない」状態だった。
     * 本テスト群が payload → 予定本体への実適用を DB の実値で恒久的に保証する。
     */
    @Nested
    @DisplayName("テスト2-B: 予約出欠の設定適用（payload_json → 予定本体）")
    class AttendanceSettingsApplied {

        /** payload_json を「その時代に書かれた生の JSON」として組み立てる（DTO 型に依存しない）。 */
        private String rawAttendancePayload(String deadlineJson, String commentOption, String minResponseRole) {
            return """
                    {"attendanceDeadline":%s,"commentOption":%s,"minResponseRole":%s}"""
                    .formatted(deadlineJson,
                            commentOption == null ? "null" : "\"" + commentOption + "\"",
                            minResponseRole == null ? "null" : "\"" + minResponseRole + "\"");
        }

        @Test
        @DisplayName("AC-2/AC-3: payload の締切・コメント設定・最低応答ロールが materialize で予定へ適用される")
        void payloadの出欠設定がmaterializeで適用される() throws Exception {
            // Arrange
            seedUsersAndRoles();
            ScheduleEntity schedule = persistScheduleWithAttendanceSettings(
                    TEAM_ID, LocalDateTime.now().plusDays(5),
                    null, CommentOption.OPTIONAL, MinResponseRole.MEMBER_PLUS);

            // ユーザーが指定した締切（JST オフセット付き＝FE が実際に送る形）
            OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.ofHours(9))
                    .plusDays(3).withNano(0);
            LocalDateTime expectedDeadlineJst =
                    deadline.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

            String payload = rawAttendancePayload(
                    "\"" + deadline + "\"", "REQUIRED", "ADMIN_ONLY");
            ScheduleScheduledTaskEntity task = persistTask(
                    schedule.getId(), ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().minusMinutes(3), payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert: タスクが CREATED（materialize 成功）
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("materialize 成功（attempt=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);

            // Assert: 予定本体に出欠設定が実適用されていること（DB 実値）
            ScheduleEntity after = scheduleRepository.findById(schedule.getId()).orElseThrow();
            assertThat(after.getAttendanceDeadline())
                    .as("AC-2: ユーザー指定の回答締切が予定へ適用されること")
                    .isEqualTo(expectedDeadlineJst);
            assertThat(after.getCommentOption())
                    .as("AC-3: コメント設定が予定へ適用されること")
                    .isEqualTo(CommentOption.REQUIRED);
            assertThat(after.getMinResponseRole())
                    .as("AC-3: 最低応答ロールが予定へ適用されること")
                    .isEqualTo(MinResponseRole.ADMIN_ONLY);

            // Assert: 従来どおり出欠レコードも生成されること
            assertThat(attendanceRepository.countByScheduleId(schedule.getId()))
                    .as("出欠募集そのものは従来どおり動作すること")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("AC-5: 非JSTオフセットで書かれた既存 payload_json も materialize できる（後方互換）")
        void 旧オフセット付きpayloadでもmaterializeできる() throws Exception {
            // Arrange: 旧 LocalDateTimeTimezoneSerializer はリクエストユーザーの TZ で書き出すため、
            // 既存行には -04:00（New York）などの非 JST オフセットが混在しうる。
            seedUsersAndRoles();
            ScheduleEntity schedule = persistScheduleWithAttendanceSettings(
                    TEAM_ID, LocalDateTime.now().plusDays(6),
                    null, CommentOption.OPTIONAL, MinResponseRole.MEMBER_PLUS);

            OffsetDateTime deadlineJst = OffsetDateTime.now(ZoneOffset.ofHours(9))
                    .plusDays(4).withNano(0);
            OffsetDateTime legacyNewYork = deadlineJst.withOffsetSameInstant(ZoneOffset.ofHours(-4));
            LocalDateTime expectedDeadlineJst =
                    deadlineJst.atZoneSameInstant(ZoneId.of("Asia/Tokyo")).toLocalDateTime();

            String payload = rawAttendancePayload(
                    "\"" + legacyNewYork + "\"", "REQUIRED", null);
            ScheduleScheduledTaskEntity task = persistTask(
                    schedule.getId(), ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().minusMinutes(2), payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("AC-5: 旧オフセット付き payload でも失敗しないこと（attempt=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);

            ScheduleEntity after = scheduleRepository.findById(schedule.getId()).orElseThrow();
            assertThat(after.getAttendanceDeadline())
                    .as("AC-5: 非 JST オフセットでも同一の瞬間として JST へ正規化されること")
                    .isEqualTo(expectedDeadlineJst);
            assertThat(after.getMinResponseRole())
                    .as("payload で未指定の項目は既存値を保つこと")
                    .isEqualTo(MinResponseRole.MEMBER_PLUS);
        }

        @Test
        @DisplayName("AC-5: オフセット無しで書かれた既存 payload_json も JST として materialize できる")
        void 旧オフセット無しpayloadでもmaterializeできる() throws Exception {
            // Arrange
            seedUsersAndRoles();
            ScheduleEntity schedule = persistScheduleWithAttendanceSettings(
                    TEAM_ID, LocalDateTime.now().plusDays(7),
                    null, CommentOption.OPTIONAL, MinResponseRole.MEMBER_PLUS);

            LocalDateTime deadlineLocal = LocalDateTime.now().plusDays(5).withNano(0).withSecond(0);
            String payload = rawAttendancePayload(
                    "\"" + deadlineLocal + "\"", null, null);
            ScheduleScheduledTaskEntity task = persistTask(
                    schedule.getId(), ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().minusMinutes(2), payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus())
                    .as("AC-5: オフセット無し payload でも失敗しないこと（attempt=%d, lastError=%s）"
                            .formatted(afterTask.getAttemptCount(), afterTask.getLastError()))
                    .isEqualTo(ScheduledTaskStatus.CREATED);

            ScheduleEntity after = scheduleRepository.findById(schedule.getId()).orElseThrow();
            assertThat(after.getAttendanceDeadline())
                    .as("AC-5: オフセット無しは JST（サーバー既定 TZ）として解釈されること")
                    .isEqualTo(deadlineLocal);
        }

        @Test
        @DisplayName("AC-4: 締切等を省略した payload では予定の既存設定を上書きしない（非退行）")
        void 設定未指定のpayloadは既存設定を壊さない() throws Exception {
            // Arrange: 予定側に既存の締切・設定がある
            seedUsersAndRoles();
            LocalDateTime existingDeadline = LocalDateTime.now().plusDays(9).withNano(0).withSecond(0);
            ScheduleEntity schedule = persistScheduleWithAttendanceSettings(
                    TEAM_ID, LocalDateTime.now().plusDays(10),
                    existingDeadline, CommentOption.HIDDEN, MinResponseRole.SUPPORTER_PLUS);

            String payload = rawAttendancePayload("null", null, null);
            ScheduleScheduledTaskEntity task = persistTask(
                    schedule.getId(), ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().minusMinutes(1), payload);

            // Act
            scheduledTaskBatchService.materializeOne(task);

            // Assert
            ScheduleScheduledTaskEntity afterTask =
                    scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(afterTask.getStatus()).isEqualTo(ScheduledTaskStatus.CREATED);

            ScheduleEntity after = scheduleRepository.findById(schedule.getId()).orElseThrow();
            assertThat(after.getAttendanceDeadline())
                    .as("AC-4: 未指定なら既存の締切を維持すること")
                    .isEqualTo(existingDeadline);
            assertThat(after.getCommentOption())
                    .as("AC-4: 未指定なら既存のコメント設定を維持すること")
                    .isEqualTo(CommentOption.HIDDEN);
            assertThat(after.getMinResponseRole())
                    .as("AC-4: 未指定なら既存の最低応答ロールを維持すること")
                    .isEqualTo(MinResponseRole.SUPPORTER_PLUS);
            assertThat(attendanceRepository.countByScheduleId(schedule.getId()))
                    .as("AC-4: 出欠募集そのものは従来どおり動作すること")
                    .isEqualTo(2L);
        }
    }

    // ========================================================================
    // テスト 3: 即時出欠の配線根治
    // ========================================================================

    @Nested
    @DisplayName("テスト3: 即時出欠の配線根治（ScheduleCreatedEventリスナー）")
    class ImmediateAttendanceSolicitation {

        /**
         * attendanceRequired=true・予約タスク無し → onScheduleCreated 直呼び → 出欠レコード生成。
         *
         * <p><b>回帰防止</b>: 機能55 第一陣まで ScheduleCreatedEvent が出欠募集に配線されておらず、
         * attendanceRequired=true で予定を作っても出欠レコードが生成されなかった。
         * このテストがその根治（第二陣）を恒久的に保証する。</p>
         *
         * <p>AFTER_COMMIT + @Async のリスナーを同期テストで検証するため、
         * {@link ScheduleAttendanceSolicitationEventListener#onScheduleCreated} を直接呼び出す。
         * リスナー内部は独立トランザクションを持たないため（呼び出し元 Tx に参加する）、
         * 呼び出し後にコミットが行われれば出欠レコードが永続化される。
         * TransactionTemplate でリスナー直呼びをラップしてコミットする。</p>
         */
        @Test
        @DisplayName("attendanceRequired=true・予約タスク無し → solicitationEventListener直呼び → 出欠レコード生成")
        void immediateAttendance_noScheduledTask_opensOnCreatedEvent() {
            // Arrange
            seedUsersAndRoles();
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, true,
                    LocalDateTime.now().plusHours(3));

            // 予約タスク（PENDING ATTENDANCE）は作らない → 即時募集ルートに入る

            // Act: AFTER_COMMIT @Async の代わりに直呼び。
            //
            // 【重要】solicitationEventListener は Spring プロキシを通して @Autowired されているため、
            // プロキシ経由でメソッドを呼ぶと @Async が効いて別スレッドで非同期実行される。
            // 非同期スレッドでは TransactionTemplate の Tx とは独立した Tx が開かれるが、
            // テストのアサーションは TransactionTemplate.execute 完了直後に実行されるため、
            // 非同期スレッドのコミットが完了していない場合がある（0L になる race condition）。
            //
            // 解決策: AopTestUtils.getUltimateTargetObject でプロキシを剥がして実インスタンスを取得し、
            // @Async をバイパスして同期実行する。リスナーは @TransactionalEventListener(AFTER_COMMIT)
            // なので直接呼び出しでも正しく動作する（直接呼び出し時はアノテーションの phase 制約は無関係）。
            // 実インスタンスのメソッドは @Transactional を持たないため、TransactionTemplate の Tx に参加する。
            ScheduleAttendanceSolicitationEventListener rawListener =
                    AopTestUtils.getUltimateTargetObject(solicitationEventListener);
            com.mannschaft.app.schedule.event.ScheduleCreatedEvent event =
                    new com.mannschaft.app.schedule.event.ScheduleCreatedEvent(
                            schedule.getId(), "TEAM", TEAM_ID, CREATED_BY, true);
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.execute(status -> {
                rawListener.onScheduleCreated(event);
                return null;
            });

            // Assert: 出欠レコードが生成されていること
            long count = attendanceRepository.countByScheduleId(schedule.getId());
            assertThat(count)
                    .as("即時出欠募集でメンバー分の出欠レコードが生成されること（回帰防止）")
                    .isEqualTo(2L);
        }

        @Test
        @DisplayName("PENDING ATTENDANCE 予約タスクがある場合 → 即時募集しない（バッチに委ねる）")
        void immediateAttendance_hasPendingTask_skipsImmediate() throws Exception {
            // Arrange
            seedUsersAndRoles();
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, true,
                    LocalDateTime.now().plusHours(4));

            // PENDING ATTENDANCE タスクを未来時刻で作成（即時募集をスキップするケース）
            String payload = objectMapper.writeValueAsString(
                    new ScheduleScheduledTaskService.AttendancePayload(null, null, null));
            persistTask(schedule.getId(), ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().plusHours(1), payload);

            // Act: @Async をバイパスして実インスタンスを直接呼ぶ（上のテストと同様の理由）
            ScheduleAttendanceSolicitationEventListener rawListener =
                    AopTestUtils.getUltimateTargetObject(solicitationEventListener);
            com.mannschaft.app.schedule.event.ScheduleCreatedEvent event =
                    new com.mannschaft.app.schedule.event.ScheduleCreatedEvent(
                            schedule.getId(), "TEAM", TEAM_ID, CREATED_BY, true);
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.execute(status -> {
                rawListener.onScheduleCreated(event);
                return null;
            });

            // Assert: 出欠レコードは生成されない（バッチ到来まで待機）
            long count = attendanceRepository.countByScheduleId(schedule.getId());
            assertThat(count)
                    .as("PENDING ATTENDANCE タスクがある場合は即時募集しないこと")
                    .isEqualTo(0L);
        }
    }

    // ========================================================================
    // テスト 4: 共有リマインダー発火根治
    // ========================================================================

    @Nested
    @DisplayName("テスト4: 共有リマインダー発火根治（ScheduleReminderBatchService）")
    class SharedReminderBatch {

        /**
         * <p><b>回帰防止</b>: 機能55 第一陣まで共有予定リマインダーバッチが存在せず、
         * is_sent=false のリマインダーは永遠に発火しなかった。このテストが根治（第二陣）を保証する。</p>
         */
        @Test
        @DisplayName("ABSOLUTE リマインダー（remindAt=過去）→ runBatch() → is_sent=true")
        void absoluteReminder_pastTime_markedSent() {
            // Arrange: schedule（schedule_attendances が空でも sendReminder は動く）
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, false,
                    LocalDateTime.now().plusHours(1));
            ScheduleAttendanceReminderEntity reminder = persistReminder(
                    schedule.getId(), LocalDateTime.now().minusMinutes(2));

            // Act
            scheduleReminderBatchService.runBatch();

            // Assert: is_sent が true になっていること（回帰防止）
            ScheduleAttendanceReminderEntity after =
                    reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getIsSent())
                    .as("ABSOLUTE リマインダーが発火後 is_sent=true になること（回帰防止）")
                    .isTrue();
        }

        @Test
        @DisplayName("RELATIVE リマインダー（start-N分前到来）→ runBatch() → is_sent=true")
        void relativeReminder_dueTime_markedSent() {
            // Arrange: 開始が1分後・remindBeforeMinutes=10 → 実効時刻=10分前=過去 → due
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, false,
                    LocalDateTime.now().plusMinutes(1));
            ScheduleAttendanceReminderEntity reminder = persistRelativeReminder(
                    schedule.getId(), 10);

            // Act
            scheduleReminderBatchService.runBatch();

            // Assert
            ScheduleAttendanceReminderEntity after =
                    reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getIsSent())
                    .as("RELATIVE リマインダーが実効時刻到来後 is_sent=true になること（回帰防止）")
                    .isTrue();
        }

        @Test
        @DisplayName("ABSOLUTE リマインダー（remindAt=未来）→ runBatch() → is_sent=false のまま")
        void absoluteReminder_futureTime_notSent() {
            // Arrange: 未来時刻 → due でない
            ScheduleEntity schedule = persistSchedule(TEAM_ID, null, false,
                    LocalDateTime.now().plusHours(2));
            ScheduleAttendanceReminderEntity reminder = persistReminder(
                    schedule.getId(), LocalDateTime.now().plusHours(1));

            // Act
            scheduleReminderBatchService.runBatch();

            // Assert: 未来なので送信されない
            ScheduleAttendanceReminderEntity after =
                    reminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getIsSent())
                    .as("未来のリマインダーはまだ発火しないこと")
                    .isFalse();
        }
    }

    // ========================================================================
    // テスト 5: 個人リマインダー発火根治
    // ========================================================================

    @Nested
    @DisplayName("テスト5: 個人リマインダー発火根治（PersonalScheduleReminderBatchService）")
    class PersonalReminderBatch {

        /**
         * <p><b>回帰防止</b>: 機能55 第一陣まで個人予定リマインダーバッチが存在せず、
         * notified=false のリマインダーは永遠に発火しなかった。このテストが根治（第二陣）を保証する。</p>
         */
        @Test
        @DisplayName("ABSOLUTE 個人リマインダー（remindAt=過去）→ runBatch() → notified=true")
        void absolutePersonalReminder_pastTime_notified() {
            // Arrange: 個人予定（userId あり）を作成
            Long personalUserId = 8_810L;
            ScheduleEntity schedule = persistSchedule(null, personalUserId, false,
                    LocalDateTime.now().plusHours(1));
            PersonalScheduleReminderEntity reminder = persistPersonalReminderAbsolute(
                    schedule.getId(), LocalDateTime.now().minusMinutes(3));

            // Act
            personalScheduleReminderBatchService.runBatch();

            // Assert: notified=true になること（回帰防止）
            PersonalScheduleReminderEntity after =
                    personalReminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getNotified())
                    .as("ABSOLUTE 個人リマインダーが発火後 notified=true になること（回帰防止）")
                    .isTrue();
        }

        @Test
        @DisplayName("RELATIVE 個人リマインダー（start-N分前到来）→ runBatch() → notified=true")
        void relativePersonalReminder_dueTime_notified() {
            // Arrange: 開始1分後・remindBeforeMinutes=5 → 実効=5分前=過去 → due
            Long personalUserId = 8_811L;
            ScheduleEntity schedule = persistSchedule(null, personalUserId, false,
                    LocalDateTime.now().plusMinutes(1));
            PersonalScheduleReminderEntity reminder = persistPersonalReminderRelative(
                    schedule.getId(), 5);

            // Act
            personalScheduleReminderBatchService.runBatch();

            // Assert
            PersonalScheduleReminderEntity after =
                    personalReminderRepository.findById(reminder.getId()).orElseThrow();
            assertThat(after.getNotified())
                    .as("RELATIVE 個人リマインダーが実効時刻到来後 notified=true になること（回帰防止）")
                    .isTrue();
        }
    }

    // ========================================================================
    // テスト 6: 予約タスク取消
    // ========================================================================

    @Nested
    @DisplayName("テスト6: 予約タスク取消")
    class TaskCancellation {

        @Test
        @DisplayName("CANCELLED タスク（scheduledAt=過去）→ runBatch() → materialize されずCANCELLED のまま")
        void cancelledTask_runBatch_notMaterialized() throws Exception {
            // Arrange: 過去時刻のタスクを PENDING で作り、キャンセルする
            String payload = objectMapper.writeValueAsString(
                    new ScheduleScheduledTaskService.AttendancePayload(null, null, null));
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 100, ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().minusMinutes(1), payload);

            // キャンセル（TransactionTemplate でコミット）
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.execute(status -> {
                ScheduleScheduledTaskEntity current =
                        scheduledTaskRepository.findById(task.getId()).orElseThrow();
                current.cancel();
                scheduledTaskRepository.save(current);
                return null;
            });

            // Act: バッチ実行（CANCELLED は findByStatus PENDING では取得されない）
            scheduledTaskBatchService.runBatch();

            // Assert: CANCELLED のまま変わらない
            ScheduleScheduledTaskEntity after = scheduledTaskRepository.findById(task.getId()).orElseThrow();
            assertThat(after.getStatus())
                    .as("CANCELLED タスクはバッチ実行後も CANCELLED のまま")
                    .isEqualTo(ScheduledTaskStatus.CANCELLED);
            assertThat(after.getMaterializedEntityId())
                    .as("CANCELLED タスクの materialized_entity_id は null のまま")
                    .isNull();
        }

        @Test
        @DisplayName("未来 scheduledAt の PENDING タスク → runBatch() → materialize されない")
        void futureTask_runBatch_notMaterialized() throws Exception {
            // Arrange: scheduledAt が1時間後
            String payload = objectMapper.writeValueAsString(
                    new ScheduleScheduledTaskService.AttendancePayload(null, null, null));
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 200, ScheduledTaskType.ATTENDANCE,
                    LocalDateTime.now().plusHours(1), payload);
            UUID taskId = task.getId();

            // Act
            scheduledTaskBatchService.runBatch();

            // Assert: PENDING のまま
            ScheduleScheduledTaskEntity after = scheduledTaskRepository.findById(taskId).orElseThrow();
            assertThat(after.getStatus())
                    .as("未来タスクはバッチ実行後も PENDING のまま")
                    .isEqualTo(ScheduledTaskStatus.PENDING);
        }
    }

    // ========================================================================
    // テスト 7: 失敗リトライ
    // ========================================================================

    @Nested
    @DisplayName("テスト7: 失敗リトライ — attempt_count加算・上限でFAILED確定")
    class FailureAndRetry {

        /**
         * 不正 payload（JSON としては有効だが型変換不能）の SURVEY タスクを使い、
         * materialize が何度も失敗するシナリオを再現する。
         *
         * <p>runBatch() は 1 回の実行で 1 件の失敗を記録する（REQUIRES_NEW トランザクション）。
         * MAX_ATTEMPTS=5 に達したタスクは FAILED 確定となる。</p>
         *
         * <p><b>設計確認</b>: エラーを握りつぶさず attempt_count に記録する根治治療の実施を保証する。</p>
         */
        @Test
        @DisplayName("不正 payload の SURVEY タスク → MAX_ATTEMPTS 到達で FAILED 確定")
        void invalidPayload_maxAttempts_failedStatus() {
            // Arrange: JSON は valid だが CreateSurveyRequest にデシリアライズ不能な内容
            String badPayload = "{\"invalid_key\": \"cannot_deserialize_to_CreateSurveyRequest\"}";
            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(1);

            // MAX_ATTEMPTS に到達するまで繰り返す
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 300, ScheduledTaskType.SURVEY, pastTime, badPayload);
            UUID taskId = task.getId();

            // runBatch() を MAX_ATTEMPTS 回実行する
            int maxAttempts = ScheduleScheduledTaskBatchService.MAX_ATTEMPTS;
            for (int i = 0; i < maxAttempts; i++) {
                scheduledTaskBatchService.runBatch();

                ScheduleScheduledTaskEntity current = scheduledTaskRepository.findById(taskId).orElseThrow();
                if (current.getStatus() == ScheduledTaskStatus.FAILED) {
                    break; // FAILED 確定済みならループ終了
                }
            }

            // Assert: FAILED 確定・attempt_count が MAX_ATTEMPTS 以上
            ScheduleScheduledTaskEntity after = scheduledTaskRepository.findById(taskId).orElseThrow();
            assertThat(after.getStatus())
                    .as("MAX_ATTEMPTS 到達後は FAILED になること（エラーを握りつぶさない根治治療の確認）")
                    .isEqualTo(ScheduledTaskStatus.FAILED);
            assertThat(after.getAttemptCount())
                    .as("attempt_count が MAX_ATTEMPTS 以上になること")
                    .isGreaterThanOrEqualTo(maxAttempts);
            assertThat(after.getLastError())
                    .as("失敗理由が記録されていること")
                    .isNotNull();
        }

        @Test
        @DisplayName("不正 payload の SURVEY タスク → MAX_ATTEMPTS 未満は PENDING のまま（再試行可）")
        void invalidPayload_belowMaxAttempts_remainsPending() {
            // Arrange
            String badPayload = "{\"invalid\": \"bad\"}";
            LocalDateTime pastTime = LocalDateTime.now().minusMinutes(1);
            ScheduleScheduledTaskEntity task = persistTask(
                    SCHEDULE_ID + 400, ScheduledTaskType.SURVEY, pastTime, badPayload);
            UUID taskId = task.getId();

            // 1回だけ runBatch() を実行（MAX_ATTEMPTS=5 に対して 1回 < 5回）
            scheduledTaskBatchService.runBatch();

            // Assert: 失敗後は PENDING のまま（MAX_ATTEMPTS 未達なので再試行可能）
            ScheduleScheduledTaskEntity after = scheduledTaskRepository.findById(taskId).orElseThrow();
            assertThat(after.getStatus())
                    .as("MAX_ATTEMPTS 未達時は PENDING のまま（次回バッチで再試行可能）")
                    .isEqualTo(ScheduledTaskStatus.PENDING);
            assertThat(after.getAttemptCount())
                    .as("1回失敗で attempt_count が 1 になること")
                    .isEqualTo(1);
        }
    }
}
