package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.AttendanceGenerationStatus;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.ScheduleEventCategoryErrorCode;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * スケジュールサービス。スケジュールのCRUD・横断調整を担当するファサード。
 *
 * <p>リファクタリング第6弾（2026-05-17）で 773 行を 3 クラスに分割した:</p>
 * <ul>
 *   <li>{@link ScheduleService} — CRUD・横断調整（このクラス）</li>
 *   <li>{@link ScheduleQueryService} — 取得系・カレンダー集約</li>
 *   <li>{@link ScheduleRecurrenceService} — 繰り返し展開・例外処理</li>
 * </ul>
 *
 * <p>外部公開メソッドのシグネチャは完全維持。ロジック変更なし・振る舞い変更なし。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleService {

    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";
    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";
    private static final String UPDATE_SCOPE_THIS_AND_FOLLOWING = "THIS_AND_FOLLOWING";
    private static final String UPDATE_SCOPE_ALL = "ALL";
    /**
     * スケジュール日時の保存タイムゾーン（JVM TZ と一致）。
     * サーバー保持形式の正準定義は {@link UserZoneLocalDateTimeParser#SERVER_ZONE} を参照。
     */
    private static final ZoneId STORAGE_ZONE = UserZoneLocalDateTimeParser.SERVER_ZONE;

    private final ScheduleRepository scheduleRepository;
    private final EventSurveyService eventSurveyService;
    private final ScheduleReminderService reminderService;
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduleEventCategoryService eventCategoryService;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final ScheduleQueryService queryService;
    private final ScheduleRecurrenceService recurrenceService;
    private final ScheduleScheduledTaskService scheduledTaskService;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final ScheduleTargetService scheduleTargetService;
    /**
     * 認可根治 Wave3-B6: schedule 書込（update/delete/cancel/create）・出欠閲覧の per-scope 認可に使用する。
     */
    private final AccessControlService accessControlService;

    /**
     * スケジュールを単体取得する。存在しない場合は例外をスローする。
     *
     * @param id スケジュールID
     * @return スケジュールエンティティ
     */
    public ScheduleEntity getSchedule(Long id) {
        return findScheduleOrThrow(id);
    }

    /**
     * 指定スケジュールが指定チームに属するかどうかを返す（越境窓口）。
     *
     * <p>認可根治戦役 Wave 2 トランシェ2B: performance ドメイン
     * （{@code PerformanceRecordService.createScheduleBulkRecords}）が、スケジュール連携の
     * 一括記録入力で受け取った {@code scheduleId} が path の {@code teamId} 配下かどうかを検証するための
     * 窓口。schedule ドメインの {@link ScheduleEntity} を他ドメインへ直接公開せず、
     * boolean のみを返すことでドメイン境界（CLAUDE.md）を保つ。</p>
     *
     * @param scheduleId スケジュールID
     * @param teamId     チームID
     * @return 当該チームに属するスケジュールが存在すれば true
     */
    public boolean existsByIdAndTeamId(Long scheduleId, Long teamId) {
        return scheduleRepository.findByIdAndTeamId(scheduleId, teamId).isPresent();
    }

    /**
     * 閲覧権限チェック付きでスケジュールを取得する。
     *
     * <p>F00 Phase E: {@link ContentVisibilityChecker} 経由の判定を正規化完了
     * （Phase B で試験的導入 → Phase E で旧可視性ロジックが存在しないことを確認し正式化）。
     * 本ファサードは可視性が無い場合
     * {@link com.mannschaft.app.common.visibility.VisibilityErrorCode} ベースの
     * {@link BusinessException} をスローする。</p>
     *
     * <p>旧 {@code AccessControlService.checkMembership} 直接呼び出しは廃止済み。
     * 統一された可視性判定ルール:</p>
     * <ul>
     *   <li>PERSONAL スコープのスケジュールは作成者本人のみ可視
     *       （{@link com.mannschaft.app.schedule.visibility.ScheduleVisibilityResolver} が DRAFT に正規化）。</li>
     *   <li>{@link ScheduleVisibility#ORGANIZATION} は親 ORG メンバーまで可視範囲拡張
     *       （{@link com.mannschaft.app.common.visibility.StandardVisibility#ORGANIZATION_WIDE}）。</li>
     *   <li>{@link ScheduleVisibility#CUSTOM_TEMPLATE} は F01.7 テンプレート評価へ委譲。</li>
     *   <li>SystemAdmin は全件可視（§15 D-13）。</li>
     * </ul>
     *
     * @param id     スケジュールID
     * @param userId ユーザーID
     * @return スケジュールエンティティ
     * @throws BusinessException 閲覧権限が無い、または存在しない場合
     */
    public ScheduleEntity getScheduleWithAccessCheck(Long id, Long userId) {
        // 関所(2)閲覧: F00 可視性判定に一本化する。
        //
        // CMP-017b で「配信母集団に属するなら可視性判定を迂回して見せる」OR 迂回路を撤去した。
        // 迂回路は「出欠を求めた相手には予定を見せねばならない」という正しい不変条件を守るために
        // 置かれていたが、min_view_role が閲覧判定でどこからも読まれない状態と組み合わさって
        // 「閾値を満たさない応援者に予定を見せる」抜け道になっていた。
        //
        // 書込時の不変条件（includeSupporters=TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}・
        // resolveMinViewRole / assertSupporterAxesConsistent）を先に入れたことで、
        // 配信母集団に入る応援者は必ず閲覧閾値も満たすようになり、OR は論理的に冗長になった。
        contentVisibilityChecker.assertCanView(ReferenceType.SCHEDULE, id, userId);
        return findScheduleOrThrow(id);
    }

    /**
     * チームスコープのスケジュール一覧を取得する。
     *
     * <p>F00 認可基盤連携（2026-05-29）: 閲覧者 {@code viewerUserId} の可視性で
     * 一覧を絞り込む（{@link ScheduleQueryService#listTeamSchedules} を参照）。</p>
     *
     * @param teamId       チームID
     * @param from         期間開始
     * @param to           期間終了
     * @param viewerUserId 閲覧者ユーザーID
     * @return 閲覧可能なスケジュール一覧
     */
    public List<ScheduleResponse> listTeamSchedules(
            Long teamId, LocalDateTime from, LocalDateTime to, Long viewerUserId) {
        return queryService.listTeamSchedules(teamId, from, to, viewerUserId);
    }

    /**
     * 組織スコープのスケジュール一覧を取得する。
     *
     * <p>F00 認可基盤連携（2026-05-29）: 閲覧者 {@code viewerUserId} の可視性で
     * 一覧を絞り込む（{@link ScheduleQueryService#listOrgSchedules} を参照）。</p>
     *
     * @param orgId        組織ID
     * @param from         期間開始
     * @param to           期間終了
     * @param viewerUserId 閲覧者ユーザーID
     * @return 閲覧可能なスケジュール一覧
     */
    public List<ScheduleResponse> listOrgSchedules(
            Long orgId, LocalDateTime from, LocalDateTime to, Long viewerUserId) {
        return queryService.listOrgSchedules(orgId, from, to, viewerUserId);
    }

    /**
     * スケジュールを作成する。繰り返しルールがある場合は子スケジュールを展開する。
     * attendanceRequired が true の場合は出欠レコード生成をイベント経由で発行する。
     *
     * @param req       作成リクエスト
     * @param scopeId   スコープID
     * @param scopeType スコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param userId    作成者ID
     * @return 作成されたスケジュール
     */
    @Transactional
    public ScheduleResponse createSchedule(CreateScheduleRequest req, Long scopeId,
                                           String scopeType, Long userId) {
        checkCreateScopeAccess(scopeId, scopeType, userId);
        LocalDateTime startAtJst = toJst(req.getStartAt());
        LocalDateTime endAtJst = toJst(req.getEndAt());
        LocalDateTime deadlineJst = toJst(req.getAttendanceDeadline());
        validateDateRange(startAtJst, endAtJst);

        ScheduleEntity schedule = buildScheduleEntity(req, scopeId, scopeType, userId,
                startAtJst, endAtJst, deadlineJst);
        schedule = scheduleRepository.save(schedule);
        scheduleTargetService.replaceForCreate(
                schedule, scopeType, scopeId, req.getTargetMode(), req.getTargetUserIds());

        // 繰り返しルールがある場合は子スケジュールを展開
        if (req.getRecurrenceRule() != null) {
            recurrenceService.expandRecurrenceSchedules(schedule);
        }

        // アンケート設問の保存
        if (req.getSurveys() != null && !req.getSurveys().isEmpty()) {
            eventSurveyService.createSurveys(schedule.getId(), req.getSurveys());
        }

        // リマインダーの保存
        if (req.getReminders() != null && !req.getReminders().isEmpty()) {
            reminderService.createReminders(schedule.getId(), req.getReminders());
        }

        // 機能55: 予約タスク（予約アンケート / 予約出欠募集）の登録。
        // schedule ドメイン内で PENDING タスクを保存するのみ（survey 越境の materialize はバッチ側に分離）。
        registerScheduledTasks(req, schedule);

        // イベント発行（トランザクションコミット後に発行）
        String resolvedScopeType = resolveScopeType(schedule);
        eventPublisher.publishEvent(new ScheduleCreatedEvent(
                schedule.getId(), resolvedScopeType, scopeId, userId,
                Boolean.TRUE.equals(req.getAttendanceRequired())));

        log.info("スケジュール作成: id={}, title={}, scope={}:{}", schedule.getId(), schedule.getTitle(), scopeType, scopeId);
        return toScheduleResponse(schedule);
    }

    /**
     * スケジュールを更新する。繰り返しスケジュールの場合は updateScope に応じて更新範囲を制御する。
     *
     * @param id          スケジュールID
     * @param req         更新リクエスト
     * @param updateScope 更新スコープ（THIS_ONLY / THIS_AND_FOLLOWING / ALL）
     * @param userId      操作ユーザーID
     * @return 更新されたスケジュール
     */
    @Transactional
    public ScheduleResponse updateSchedule(Long id, UpdateScheduleRequest req,
                                           String updateScope, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        checkScopeAdminAccess(schedule, userId);
        validateScheduleNotCancelled(schedule);

        if (req.getStartAt() != null || req.getEndAt() != null) {
            LocalDateTime startAt = req.getStartAt() != null ? toJst(req.getStartAt()) : schedule.getStartAt();
            LocalDateTime endAt = req.getEndAt() != null ? toJst(req.getEndAt()) : schedule.getEndAt();
            validateDateRange(startAt, endAt);
        }

        if (schedule.isRecurring() || schedule.getParentScheduleId() != null) {
            recurrenceService.updateRecurringSchedule(schedule, req, updateScope, this::applyUpdateToSchedule);
        } else {
            applyUpdateToSchedule(schedule, req);
        }

        schedule = scheduleRepository.save(schedule);
        scheduleTargetService.replaceForUpdate(
                schedule, resolveScopeType(schedule), resolveScopeId(schedule),
                req.getTargetMode(), req.getTargetUserIds());

        // 機能55 BE対応: リマインダー更新（null = 変更なし、空リスト = 全削除、非空 = 差し替え）
        if (req.getReminders() != null) {
            reminderService.updateReminders(schedule.getId(), req.getReminders());
        }

        // 機能55 BE対応: 予約タスク差分更新（surveys/attendance のどちらかが非null の場合のみ）
        if (req.getScheduledSurveys() != null || req.getScheduledAttendance() != null) {
            CalendarSyncScopeType scopeType = null;
            Long scopeId = null;
            Long organizationId = null;
            if (schedule.isTeamScope()) {
                scopeType = CalendarSyncScopeType.TEAM;
                scopeId = schedule.getTeamId();
                organizationId = resolveOrganizationIdForTeam(schedule.getTeamId());
            } else if (schedule.isOrganizationScope()) {
                scopeType = CalendarSyncScopeType.ORGANIZATION;
                scopeId = schedule.getOrganizationId();
                organizationId = schedule.getOrganizationId();
            } else {
                // PERSONAL には予約作成の概念が無いためスキップ
                log.info("予約タスク更新スキップ（PERSONALスコープ）: scheduleId={}", schedule.getId());
            }
            if (organizationId != null) {
                scheduledTaskService.updateTasksForSchedule(
                        schedule.getId(), scopeType, scopeId, organizationId, userId,
                        req.getScheduledSurveys(), req.getScheduledAttendance());
            }
        }

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new ScheduleUpdatedEvent(schedule.getId(), userId));

        log.info("スケジュール更新: id={}, updateScope={}", id, updateScope);
        return toScheduleResponse(schedule);
    }

    /**
     * スケジュールを論理削除する。繰り返しスケジュールの場合は updateScope に応じて削除範囲を制御する。
     *
     * @param id          スケジュールID
     * @param updateScope 更新スコープ（THIS_ONLY / THIS_AND_FOLLOWING / ALL）
     * @param userId      操作ユーザーID（認可チェック用）
     */
    @Transactional
    public void deleteSchedule(Long id, String updateScope, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        checkScopeAdminAccess(schedule, userId);

        if (UPDATE_SCOPE_ALL.equals(updateScope) && schedule.getParentScheduleId() != null) {
            // 親と全子を削除
            Long parentId = schedule.getParentScheduleId();
            ScheduleEntity parent = findScheduleOrThrow(parentId);
            parent.softDelete();
            scheduleRepository.save(parent);
            recurrenceService.deleteChildSchedules(parentId);
        } else if (UPDATE_SCOPE_THIS_AND_FOLLOWING.equals(updateScope) && schedule.getParentScheduleId() != null) {
            // この日以降の子を削除
            recurrenceService.deleteFollowingSchedules(schedule);
        } else {
            // 単体削除
            schedule.softDelete();
            scheduleRepository.save(schedule);
        }

        log.info("スケジュール削除: id={}, updateScope={}", id, updateScope);
    }

    /**
     * スケジュールをキャンセルする。
     *
     * @param id     スケジュールID
     * @param userId 操作ユーザーID
     */
    @Transactional
    public void cancelSchedule(Long id, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        checkScopeAdminAccess(schedule, userId);
        validateScheduleNotCancelled(schedule);

        schedule.cancel();
        scheduleRepository.save(schedule);

        // 機能55: 当該予定の PENDING 予約タスクを取り消す（未 materialize の予約をキャンセル連動）
        scheduledTaskService.cancelTasksForSchedule(schedule.getId());

        // イベント発行（トランザクションコミット後に発行）
        eventPublisher.publishEvent(new ScheduleCancelledEvent(schedule.getId(), userId));

        log.info("スケジュールキャンセル: id={}", id);
    }

    /**
     * スケジュールを複製する。
     *
     * <p><b>認可（認可根治 Wave3-B6・BOLA是正）</b>: 本メソッドは
     * {@link ScheduleCrossRefService#acceptInvitation}（クロス招待の受諾＝正当な越境複製）からも
     * 呼ばれる共有メソッドのため、ここに per-scope 認可を埋め込むと招待受諾の正当系を壊す
     * （{@code feedback_authz_gate_on_public_entry_not_shared_method}）。認可は public な複製 API の
     * 入口（{@code OrgScheduleController} / {@code TeamScheduleController} の duplicate EP）で
     * {@link #checkScopeAdminAccess(Long, Long)} を呼んで行う。</p>
     *
     * @param id     複製元スケジュールID
     * @param userId 作成者ID
     * @return 複製されたスケジュール
     */
    @Transactional
    public ScheduleResponse duplicateSchedule(Long id, Long userId) {
        ScheduleEntity source = findScheduleOrThrow(id);

        ScheduleEntity duplicate = source.toBuilder()
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(userId)
                .googleCalendarEventId(null)
                .build();

        // BaseEntity の id, createdAt, updatedAt は @PrePersist で再設定される
        duplicate = scheduleRepository.save(duplicate);

        log.info("スケジュール複製: sourceId={}, newId={}", id, duplicate.getId());
        return toScheduleResponse(duplicate);
    }

    /**
     * ユーザーの横断カレンダーを取得する。個人・チーム・組織スコープのスケジュールを統合して返す。
     *
     * @param userId ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return カレンダーエントリー一覧
     */
    public List<CalendarEntryResponse> getMyCalendar(Long userId, LocalDateTime from, LocalDateTime to) {
        return queryService.getMyCalendar(userId, from, to);
    }

    // --- パッケージプライベートメソッド（同パッケージのサービスから利用） ---

    /**
     * スケジュールを取得する。存在しない場合は例外をスローする。
     */
    ScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_NOT_FOUND));
    }

    /**
     * スケジュールに対する管理操作（update/delete/cancel/duplicate）の per-scope 認可を
     * entity 由来 scope で強制する（認可根治 Wave3-B6）。
     *
     * <p>TEAM/ORGANIZATION スコープは {@link AccessControlService#checkAdminOrAbove} で
     * 当該チーム/組織の ADMIN/DEPUTY_ADMIN のみ許可する。PERSONAL スコープ（{@code userId} 設定）は
     * ロール概念が無いため所有者本人一致で判定する
     * （{@code project_scopetype_cross_domain_personal_mismatch}: PERSONAL を membership 系 API に
     * 渡すと 500 になるため分岐が必須）。SYSTEM_ADMIN は短絡的に許可する。</p>
     *
     * <p>id 版は {@link ScheduleCrossRefService} 等、他クラスから public な操作入口で
     * BOLA 是正のために呼び出す用途で公開する（duplicateSchedule 自体には認可を埋め込まない方針の受け皿）。</p>
     *
     * @param id     スケジュールID
     * @param userId 操作ユーザーID
     * @throws BusinessException スケジュールが存在しない場合 / 権限がない場合（COMMON_002）
     */
    public void checkScopeAdminAccess(Long id, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        checkScopeAdminAccess(schedule, userId);
    }

    /**
     * {@link #checkScopeAdminAccess(Long, Long)} の entity 版（既に fetch 済みの場合に使う）。
     */
    void checkScopeAdminAccess(ScheduleEntity schedule, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (schedule.isTeamScope()) {
            accessControlService.checkAdminOrAbove(userId, schedule.getTeamId(), SCOPE_TYPE_TEAM);
        } else if (schedule.isOrganizationScope()) {
            accessControlService.checkAdminOrAbove(userId, schedule.getOrganizationId(), SCOPE_TYPE_ORGANIZATION);
        } else if (!Objects.equals(schedule.getUserId(), userId)) {
            // PERSONAL スコープ: 所有者本人以外は拒否（他ドメインの書込EP経由のID混同=BOLAを防ぐ）
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スケジュールの出欠等の閲覧操作に対する per-scope 認可を entity 由来 scope で強制する
     * （認可根治 Wave3-B6・checkMembership 水準）。{@link ScheduleAttendanceService} の
     * getAttendances / exportAttendancesCsv から呼ばれる。
     *
     * @param id     スケジュールID
     * @param userId 閲覧ユーザーID
     * @throws BusinessException スケジュールが存在しない場合 / 権限がない場合（COMMON_002）
     */
    public void checkScopeViewAccess(Long id, Long userId) {
        ScheduleEntity schedule = findScheduleOrThrow(id);
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (schedule.isTeamScope()) {
            accessControlService.checkMembership(userId, schedule.getTeamId(), SCOPE_TYPE_TEAM);
        } else if (schedule.isOrganizationScope()) {
            accessControlService.checkMembership(userId, schedule.getOrganizationId(), SCOPE_TYPE_ORGANIZATION);
        } else if (!Objects.equals(schedule.getUserId(), userId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スケジュール作成時（entity 未生成の path 由来 scope）の per-scope 認可を強制する
     * （認可根治 Wave3-B6）。TEAM/ORGANIZATION のみ ADMIN 必須。PERSONAL は
     * {@code PersonalScheduleController} 経由の自己作成が正規ルートのため無条件許可、
     * 不正な scopeType は後続の {@link #buildScheduleEntity} の switch で
     * {@link ScheduleErrorCode#INVALID_SCOPE} として弾かれる。
     */
    private void checkCreateScopeAccess(Long scopeId, String scopeType, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (SCOPE_TYPE_TEAM.equals(scopeType) || SCOPE_TYPE_ORGANIZATION.equals(scopeType)) {
            accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);
        }
    }

    // --- プライベートメソッド ---

    /**
     * 開始日時と終了日時の整合性を検証する。
     */
    private void validateDateRange(LocalDateTime startAt, LocalDateTime endAt) {
        if (startAt != null && endAt != null && !startAt.isBefore(endAt)) {
            throw new BusinessException(ScheduleErrorCode.INVALID_DATE_RANGE);
        }
    }

    /**
     * キャンセル済みスケジュールの操作を防止する。
     */
    private void validateScheduleNotCancelled(ScheduleEntity schedule) {
        if (schedule.getStatus() == ScheduleStatus.CANCELLED) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    /**
     * 作成リクエストからスケジュールエンティティを構築する。
     *
     * <p>startAtJst / endAtJst / deadlineJst は呼び出し元で
     * {@link #toJst(OffsetDateTime)} により JST LocalDateTime に変換済みのもの。</p>
     */
    /**
     * 作成時の {@code min_view_role} を解決する（CMP-017b T-2 / AC-22・AC-23）。
     *
     * <p>{@code include_supporters}（配信軸）と {@code min_view_role}（閲覧軸）は独立設定だが、
     * 「応援者に出欠を配るが応援者は予定を見られない」組み合わせは自己矛盾である。よって
     * 書込時に {@code includeSupporters = TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}} を強制し、
     * 未指定時は配信軸に整合する既定へ導出する。</p>
     *
     * <p>この不変条件が成立することで初めて「配信母集団に入る応援者は必ず閲覧閾値も満たす」が
     * 保証され、閲覧側の OR 迂回路（配信母集団に居れば閾値を無視して見せる）が論理的に冗長になる。</p>
     *
     * @param requestedMinViewRole リクエストの {@code minViewRole}（{@code null} 可＝未指定）
     * @param includeSupporters    リクエストの {@code includeSupporters}（{@code null} 可＝既定 false）
     * @return 保存すべき閾値
     * @throws BusinessException 矛盾する組み合わせが明示指定された場合（400）
     */
    private MinViewRole resolveMinViewRole(String requestedMinViewRole, Boolean includeSupporters) {
        boolean supportersIncluded = Boolean.TRUE.equals(includeSupporters);
        if (requestedMinViewRole == null) {
            // 未指定: 配信軸に整合する既定を導出する（応援者に配るなら SUPPORTER_PLUS）。
            return supportersIncluded ? MinViewRole.SUPPORTER_PLUS : MinViewRole.MEMBER_PLUS;
        }
        MinViewRole requested = MinViewRole.valueOf(requestedMinViewRole);
        assertSupporterAxesConsistent(requested, includeSupporters);
        return requested;
    }

    /**
     * 二軸（配信 × 閲覧）の不変条件を検証する（CMP-017b T-2）。
     *
     * @param minViewRole       閲覧閾値
     * @param includeSupporters 応援者を配信母集団に含めるか（{@code null} 可）
     * @throws BusinessException 応援者に配信しながら応援者が閲覧できない組み合わせの場合（400）
     */
    private void assertSupporterAxesConsistent(MinViewRole minViewRole, Boolean includeSupporters) {
        if (!Boolean.TRUE.equals(includeSupporters) || minViewRole == null) {
            return;
        }
        if (minViewRole == MinViewRole.MEMBER_PLUS || minViewRole == MinViewRole.ADMIN_ONLY) {
            throw new BusinessException(ScheduleErrorCode.INCONSISTENT_SUPPORTER_AXES);
        }
    }

    private ScheduleEntity buildScheduleEntity(CreateScheduleRequest req, Long scopeId,
                                               String scopeType, Long userId,
                                               LocalDateTime startAtJst, LocalDateTime endAtJst,
                                               LocalDateTime deadlineJst) {
        String recurrenceRuleJson = null;
        if (req.getRecurrenceRule() != null) {
            recurrenceRuleJson = recurrenceService.serializeRecurrenceRule(req.getRecurrenceRule());
        }

        // カテゴリスコープ整合性チェック（F03.10）
        Long teamId = SCOPE_TYPE_TEAM.equals(scopeType) ? scopeId : null;
        Long orgId = SCOPE_TYPE_ORGANIZATION.equals(scopeType) ? scopeId : null;
        eventCategoryService.validateCategoryScope(teamId, orgId, req.getEventCategoryId());

        // academic_year が指定されている場合の日付整合性チェック（F03.10）
        if (req.getAcademicYear() != null) {
            validateAcademicYearRange(startAtJst, req.getAcademicYear());
        }

        ScheduleEntity.ScheduleEntityBuilder builder = ScheduleEntity.builder()
                .title(req.getTitle())
                .description(req.getDescription())
                .location(req.getLocation())
                .startAt(startAtJst)
                .endAt(endAtJst)
                .allDay(req.getAllDay())
                .eventType(EventType.valueOf(req.getEventType()))
                .visibility(req.getVisibility() != null
                        ? ScheduleVisibility.valueOf(req.getVisibility()) : ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(resolveMinViewRole(req.getMinViewRole(), req.getIncludeSupporters()))
                .minResponseRole(req.getMinResponseRole() != null
                        ? MinResponseRole.valueOf(req.getMinResponseRole()) : MinResponseRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .attendanceRequired(req.getAttendanceRequired())
                .includeSupporters(req.getIncludeSupporters() != null
                        ? req.getIncludeSupporters() : false)
                .teamBreakdownEnabled(req.getTeamBreakdownEnabled() != null
                        ? req.getTeamBreakdownEnabled() : false)
                .attendanceDeadline(deadlineJst)
                .commentOption(req.getCommentOption() != null
                        ? CommentOption.valueOf(req.getCommentOption()) : CommentOption.OPTIONAL)
                .eventCategoryId(req.getEventCategoryId())
                .academicYear(req.getAcademicYear() != null ? req.getAcademicYear().shortValue() : null)
                .recurrenceRule(recurrenceRuleJson)
                .createdBy(userId);

        // スコープ設定（XOR制約: teamId, organizationId, userId のいずれか1つのみ設定）
        switch (scopeType) {
            case SCOPE_TYPE_TEAM -> builder.teamId(scopeId);
            case SCOPE_TYPE_ORGANIZATION -> builder.organizationId(scopeId);
            case SCOPE_TYPE_PERSONAL -> builder.userId(scopeId);
            default -> throw new BusinessException(ScheduleErrorCode.INVALID_SCOPE);
        }

        return builder.build();
    }

    /**
     * スケジュールに更新リクエストの内容を適用する。
     *
     * <p>startAt / endAt / attendanceDeadline は OffsetDateTime で受け取り、
     * JST LocalDateTime に変換して Entity に設定する。</p>
     */
    private void applyUpdateToSchedule(ScheduleEntity schedule, UpdateScheduleRequest req) {
        ScheduleEntity.ScheduleEntityBuilder builder = schedule.toBuilder();

        if (req.getTitle() != null) builder.title(req.getTitle());
        if (req.getDescription() != null) builder.description(req.getDescription());
        if (req.getLocation() != null) builder.location(req.getLocation());
        if (req.getStartAt() != null) builder.startAt(toJst(req.getStartAt()));
        if (req.getEndAt() != null) builder.endAt(toJst(req.getEndAt()));
        if (req.getAllDay() != null) builder.allDay(req.getAllDay());
        if (req.getEventType() != null) builder.eventType(EventType.valueOf(req.getEventType()));
        if (req.getVisibility() != null) builder.visibility(ScheduleVisibility.valueOf(req.getVisibility()));
        if (req.getMinViewRole() != null) {
            MinViewRole requested = MinViewRole.valueOf(req.getMinViewRole());
            // 二軸の不変条件（CMP-017b T-2）: UpdateScheduleRequest は includeSupporters を持たないため、
            // 更新経路で不変条件を破りうるのは «既存 include_supporters=TRUE の行の閾値を引き上げる» 側のみ。
            assertSupporterAxesConsistent(requested, schedule.getIncludeSupporters());
            builder.minViewRole(requested);
        }
        if (req.getMinResponseRole() != null) builder.minResponseRole(MinResponseRole.valueOf(req.getMinResponseRole()));
        if (req.getAttendanceRequired() != null) builder.attendanceRequired(req.getAttendanceRequired());
        if (req.getAttendanceDeadline() != null) builder.attendanceDeadline(toJst(req.getAttendanceDeadline()));
        if (req.getCommentOption() != null) builder.commentOption(CommentOption.valueOf(req.getCommentOption()));

        // F03.10 行事カテゴリ・年度の更新
        // eventCategoryId が指定されている（値あり）場合のみ更新する。null は「未指定」として扱い更新しない。
        // カテゴリを解除したい場合は PATCH に "event_category_id": null を送信し、
        // ScheduleCommonController 側で別途 clearEventCategory エンドポイントを提供する。
        if (req.getEventCategoryId() != null) {
            eventCategoryService.validateCategoryScope(
                    schedule.getTeamId(), schedule.getOrganizationId(), req.getEventCategoryId());
            builder.eventCategoryId(req.getEventCategoryId());
        }
        if (req.getAcademicYear() != null) {
            LocalDateTime effectiveStartAt = req.getStartAt() != null ? toJst(req.getStartAt()) : schedule.getStartAt();
            validateAcademicYearRange(effectiveStartAt, req.getAcademicYear());
            builder.academicYear(req.getAcademicYear().shortValue());
        }

        ScheduleEntity updated = builder.build();
        scheduleRepository.save(updated);
    }

    /**
     * start_at が指定された年度の範囲内かを検証する（F03.10）。
     *
     * @param startAt      開始日時
     * @param academicYear 年度
     * @throws BusinessException 範囲外の場合
     */
    private void validateAcademicYearRange(LocalDateTime startAt, int academicYear) {
        LocalDate yearStart = LocalDate.of(academicYear, 4, 1);
        LocalDate yearEnd = LocalDate.of(academicYear + 1, 3, 31);
        LocalDate startDate = startAt.toLocalDate();
        if (startDate.isBefore(yearStart) || startDate.isAfter(yearEnd)) {
            throw new BusinessException(ScheduleEventCategoryErrorCode.ACADEMIC_YEAR_DATE_MISMATCH);
        }
    }

    /**
     * 機能55: 予約タスク（予約アンケート / 予約出欠募集）を登録する。
     *
     * <p>TEAM / ORGANIZATION スコープのみ対象。PERSONAL には予約作成の概念が無いためスキップする。
     * テナントキー {@code organizationId} を解決して {@link ScheduleScheduledTaskService#registerTasks}
     * へ委譲する。survey ドメインへの越境（materialize）はバッチ側に分離済みのため、本メソッドは
     * schedule ドメイン内に閉じる（CLAUDE.md 原則5）。</p>
     *
     * @param req      作成リクエスト
     * @param schedule 保存済みの予定エンティティ
     */
    private void registerScheduledTasks(CreateScheduleRequest req, ScheduleEntity schedule) {
        boolean hasSurveys = req.getScheduledSurveys() != null && !req.getScheduledSurveys().isEmpty();
        boolean hasAttendance = req.getScheduledAttendance() != null;
        if (!hasSurveys && !hasAttendance) {
            return;
        }

        CalendarSyncScopeType scopeType;
        Long scopeId;
        Long organizationId;
        if (schedule.isTeamScope()) {
            scopeType = CalendarSyncScopeType.TEAM;
            scopeId = schedule.getTeamId();
            organizationId = resolveOrganizationIdForTeam(schedule.getTeamId());
        } else if (schedule.isOrganizationScope()) {
            scopeType = CalendarSyncScopeType.ORGANIZATION;
            scopeId = schedule.getOrganizationId();
            organizationId = schedule.getOrganizationId();
        } else {
            // PERSONAL には予約作成の概念が無い
            log.info("予約タスク登録スキップ（PERSONALスコープ）: scheduleId={}", schedule.getId());
            return;
        }

        if (organizationId == null) {
            // テナントキーが解決できない場合は予約タスクを成立させない（壊れた状態を保存しない）
            throw new BusinessException(ScheduleErrorCode.INVALID_SCOPE);
        }

        scheduledTaskService.registerTasks(
                schedule.getId(), scopeType, scopeId, organizationId, schedule.getCreatedBy(),
                req.getScheduledSurveys(), req.getScheduledAttendance());
    }

    /**
     * チームの所属組織 ID（テナントキー）を解決する。
     *
     * <p>team→org は {@code team_org_memberships}（status=ACTIVE）で管理されるため
     * {@link TeamOrgMembershipRepository#findOrganizationIdByTeamIdIn} で解決する。</p>
     *
     * @param teamId チーム ID
     * @return 所属組織 ID（見つからない場合 null）
     */
    private Long resolveOrganizationIdForTeam(Long teamId) {
        return teamOrgMembershipRepository
                .findOrganizationIdByTeamIdIn(java.util.Set.of(teamId))
                .get(teamId);
    }

    /**
     * OffsetDateTime を JST の LocalDateTime に変換する。
     *
     * <p>クライアントから受け取った TZ 付き日時を JVM TZ（Asia/Tokyo）に変換する。
     * null の場合は null を返す（部分更新セマンティクスを壊さないため）。</p>
     *
     * @param odt クライアント TZ 付き日時
     * @return JST LocalDateTime、または null
     */
    private static LocalDateTime toJst(OffsetDateTime odt) {
        if (odt == null) return null;
        return odt.atZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
    }

    /**
     * スケジュールのスコープ種別を解決する。
     */
    private String resolveScopeType(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) return SCOPE_TYPE_TEAM;
        if (schedule.isOrganizationScope()) return SCOPE_TYPE_ORGANIZATION;
        return SCOPE_TYPE_PERSONAL;
    }

    private Long resolveScopeId(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) return schedule.getTeamId();
        if (schedule.isOrganizationScope()) return schedule.getOrganizationId();
        return schedule.getUserId();
    }

    /**
     * エンティティをスケジュール一覧用レスポンスDTOに変換する。
     */
    private ScheduleResponse toScheduleResponse(ScheduleEntity entity) {
        EventCategoryResponse categoryResponse = resolveEventCategoryResponse(entity.getEventCategoryId());
        return ScheduleResponse.builder()
                .id(entity.getId())
                .content(new ScheduleResponse.ScheduleContentDto(
                        entity.getTitle(),
                        entity.getStatus().name(),
                        entity.getEventType().name(),
                        entity.getLocation(),
                        entity.getAttendanceRequired()))
                .time(new ScheduleResponse.ScheduleTimeDto(
                        entity.getStartAt(), entity.getEndAt(), entity.getAllDay()))
                .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                .academic(new ScheduleResponse.ScheduleAcademicDto(
                        categoryResponse,
                        entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null,
                        entity.getSourceScheduleId()))
                .audit(new ScheduleResponse.ScheduleAuditDto(entity.getCreatedAt(), null))
                .myAttendanceStatus(null)
                .build();
    }

    /**
     * eventCategoryId から EventCategoryResponse を生成する。null の場合は null を返す。
     */
    private EventCategoryResponse resolveEventCategoryResponse(Long eventCategoryId) {
        if (eventCategoryId == null) {
            return null;
        }
        try {
            var cat = eventCategoryService.getById(eventCategoryId);
            String scope = cat.isTeamScope() ? "TEAM" : "ORGANIZATION";
            return new EventCategoryResponse(
                    cat.getId(), cat.getName(), cat.getColor(), cat.getIcon(),
                    cat.getIsDayOffCategory(), cat.getSortOrder(), scope);
        } catch (Exception e) {
            // カテゴリが削除済みの場合は null を返す
            return null;
        }
    }
}
