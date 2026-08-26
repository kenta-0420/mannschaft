package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.IncidentStatus;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.incident.event.IncidentReportedEvent;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.incident.repository.MaintenanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * メンテナンススケジュール管理サービス。
 * 定期メンテナンスのCRUD・手動トリガーを担う。
 */
@Slf4j
@Service("incidentMaintenanceScheduleService")
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MaintenanceScheduleService {

    private final MaintenanceScheduleRepository scheduleRepository;
    private final IncidentRepository incidentRepository;
    private final DomainEventPublisher eventPublisher;

    /** 認可根治戦役 Wave3-B3: メンテナンススケジュール管理への認可敷設で使用する。 */
    private final AccessControlService accessControlService;

    // ========================================
    // DTOクラス定義
    // ========================================

    /** スケジュール作成リクエスト */
    public record CreateMaintenanceScheduleRequest(
            String scopeType,
            Long scopeId,
            Long categoryId,
            String title,
            String description,
            String cronExpression,
            Boolean isActive
    ) {}

    /** スケジュール更新リクエスト */
    public record UpdateMaintenanceScheduleRequest(
            String title,
            String description,
            String cronExpression,
            Boolean isActive
    ) {}

    /** スケジュールレスポンス */
    public record MaintenanceScheduleResponse(
            Long id,
            String title,
            String cronExpression,
            Boolean isActive,
            LocalDateTime lastTriggeredAt,
            LocalDateTime nextTriggerAt,
            LocalDateTime createdAt
    ) {
        public static MaintenanceScheduleResponse from(MaintenanceScheduleEntity entity) {
            // 次回実行日時を算出（nextExecutionDateからLocalDateTimeに変換）
            LocalDateTime nextTriggerAt = entity.getNextExecutionDate() != null
                    ? entity.getNextExecutionDate().atStartOfDay()
                    : null;
            return new MaintenanceScheduleResponse(
                    entity.getId(),
                    entity.getName(),
                    entity.getCronExpression(),
                    entity.getIsActive(),
                    null,       // lastTriggeredAtはエンティティに未定義のため暫定null
                    nextTriggerAt,
                    entity.getCreatedAt()
            );
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * メンテナンススケジュールを作成する。
     * cronExpressionをSpring CronExpressionでバリデーションする。
     *
     * @param createdBy 作成者ユーザーID
     * @param req       作成リクエスト
     * @return 作成したスケジュールレスポンス
     */
    @Transactional
    public MaintenanceScheduleResponse createSchedule(Long createdBy, CreateMaintenanceScheduleRequest req) {
        // 認可: ADMIN相当（scopeId/scopeTypeはリクエスト由来）
        accessControlService.checkAdminOrAbove(createdBy, req.scopeId(), req.scopeType());

        // CRON式のバリデーション
        validateCronExpression(req.cronExpression());

        // 次回実行日を算出
        LocalDate nextExecutionDate = calcNextExecutionDate(req.cronExpression());

        MaintenanceScheduleEntity entity = MaintenanceScheduleEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .categoryId(req.categoryId())
                .name(req.title())
                .description(req.description())
                .cronExpression(req.cronExpression())
                .nextExecutionDate(nextExecutionDate)
                .isActive(req.isActive() != null ? req.isActive() : true)
                .createdBy(createdBy)
                .build();

        MaintenanceScheduleEntity saved = scheduleRepository.save(entity);
        log.info("メンテナンススケジュール作成: id={}, scope={}/{}, name={}",
                saved.getId(), req.scopeType(), req.scopeId(), req.title());
        return MaintenanceScheduleResponse.from(saved);
    }

    /**
     * スコープに紐づく有効スケジュール一覧を取得する。
     *
     * <p>認可: ADMIN相当（scopeId/scopeTypeはクエリパラメータ由来）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param userId    呼び出しユーザーID
     * @return スケジュールレスポンス一覧
     */
    public List<MaintenanceScheduleResponse> listSchedules(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        return scheduleRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId)
                .stream()
                .map(MaintenanceScheduleResponse::from)
                .toList();
    }

    /**
     * メンテナンススケジュールを更新する。
     *
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     スケジュールID
     * @param req    更新リクエスト
     * @param userId 呼び出しユーザーID
     * @return 更新後スケジュールレスポンス
     */
    @Transactional
    public MaintenanceScheduleResponse updateSchedule(Long id, UpdateMaintenanceScheduleRequest req, Long userId) {
        MaintenanceScheduleEntity schedule = findScheduleOrThrow(id);
        requireMemberOrConceal(schedule, userId);
        accessControlService.checkAdminOrAbove(userId, schedule.getScopeId(), schedule.getScopeType());

        // CRON式が変更される場合はバリデーション
        if (req.cronExpression() != null) {
            validateCronExpression(req.cronExpression());
            schedule.updateCronExpression(req.cronExpression());
            // 次回実行日を再計算
            schedule.updateNextExecutionDate(calcNextExecutionDate(req.cronExpression()));
        }

        // isActiveを更新（指定がある場合）
        if (req.isActive() != null) {
            if (req.isActive()) {
                schedule.activate();
            } else {
                schedule.deactivate();
            }
        }

        MaintenanceScheduleEntity saved = scheduleRepository.save(schedule);
        log.info("メンテナンススケジュール更新: id={}", id);
        return MaintenanceScheduleResponse.from(saved);
    }

    /**
     * メンテナンススケジュールを論理削除する。
     *
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     スケジュールID
     * @param userId 呼び出しユーザーID
     */
    @Transactional
    public void deleteSchedule(Long id, Long userId) {
        MaintenanceScheduleEntity schedule = findScheduleOrThrow(id);
        requireMemberOrConceal(schedule, userId);
        accessControlService.checkAdminOrAbove(userId, schedule.getScopeId(), schedule.getScopeType());

        schedule.softDelete();
        scheduleRepository.save(schedule);
        log.info("メンテナンススケジュール論理削除: id={}", id);
    }

    /**
     * メンテナンススケジュールを手動トリガーする。
     * lastTriggeredAtを更新し、IncidentEntityを1件生成する。
     *
     * <p>認可: ADMIN相当。entity 由来 scope に非所属なら存在秘匿のため 404、
     * 所属しているが ADMIN でない場合は 403。</p>
     *
     * @param id     スケジュールID
     * @param userId 呼び出しユーザーID
     * @return 作成されたインシデントのレスポンス
     */
    @Transactional
    public IncidentService.IncidentResponse triggerManually(Long id, Long userId) {
        MaintenanceScheduleEntity schedule = findScheduleOrThrow(id);
        requireMemberOrConceal(schedule, userId);
        accessControlService.checkAdminOrAbove(userId, schedule.getScopeId(), schedule.getScopeType());

        // 次回実行日を更新（次のCRON実行日）
        LocalDate nextDate = calcNextExecutionDate(schedule.getCronExpression());
        schedule.updateNextExecutionDate(nextDate);
        scheduleRepository.save(schedule);

        // インシデントを自動生成
        IncidentEntity incident = IncidentEntity.builder()
                .scopeType(schedule.getScopeType())
                .scopeId(schedule.getScopeId())
                .categoryId(schedule.getCategoryId())
                .title("[定期メンテナンス] " + schedule.getName())
                .description(schedule.getDescription())
                .status(IncidentStatus.REPORTED.name())
                .priority("MEDIUM")
                .isSlaBreached(false)
                .reportedBy(schedule.getCreatedBy())
                .build();

        IncidentEntity saved = incidentRepository.save(incident);
        log.info("メンテナンス手動トリガー: scheduleId={}, incidentId={}", id, saved.getId());

        // IncidentReportedEvent発行
        eventPublisher.publish(new IncidentReportedEvent(
                saved.getId(),
                saved.getScopeType(),
                saved.getScopeId(),
                saved.getTitle(),
                saved.getPriority(),
                saved.getReportedBy()
        ));

        return IncidentService.IncidentResponse.from(saved);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDでスケジュールを取得する。見つからない場合は INCIDENT_009 例外をスロー。
     */
    public MaintenanceScheduleEntity findScheduleOrThrow(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException(IncidentErrorCode.INCIDENT_009));
    }

    /**
     * 認可根治戦役 Wave3-B3 BOLA是正: ID 直指定 EP で使う共通ガード。
     * URL に scope が現れないため、entity を先に fetch した上で呼び出しユーザーが
     * entity 由来 scope のメンバーであることを検証する。非メンバーは越境 ID の存在を秘匿するため、
     * 通常の {@code checkMembership}（403）ではなく entity の NOT_FOUND コード（404）を投げる。
     */
    private void requireMemberOrConceal(MaintenanceScheduleEntity schedule, Long userId) {
        if (!accessControlService.isMember(userId, schedule.getScopeId(), schedule.getScopeType())) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_009);
        }
    }

    /**
     * CRON式を検証する。無効な場合は INCIDENT_010 例外をスロー。
     *
     * @param cronExpression 検証するCRON式
     */
    private void validateCronExpression(String cronExpression) {
        try {
            CronExpression.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(IncidentErrorCode.INCIDENT_010);
        }
    }

    /**
     * CRON式から次回実行日を算出する。
     *
     * @param cronExpression CRON式
     * @return 次回実行日（LocalDate）
     */
    private LocalDate calcNextExecutionDate(String cronExpression) {
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"));
            ZonedDateTime next = cron.next(now);
            return next != null ? next.toLocalDate() : LocalDate.now().plusDays(1);
        } catch (Exception e) {
            return LocalDate.now().plusDays(1);
        }
    }
}
