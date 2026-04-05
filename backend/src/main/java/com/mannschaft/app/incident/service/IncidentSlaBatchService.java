package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.incident.IncidentStatus;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.incident.event.IncidentReportedEvent;
import com.mannschaft.app.incident.event.IncidentSlaBreachedEvent;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import com.mannschaft.app.incident.repository.IncidentQueryRepository;
import com.mannschaft.app.incident.repository.IncidentRepository;
import com.mannschaft.app.incident.repository.MaintenanceScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * インシデントSLAチェック・定期メンテナンス自動生成バッチサービス。
 * 毎時0分（JST）に実行し、SLA超過チェックと定期メンテナンスの自動インシデント生成を行う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentSlaBatchService {

    private final IncidentRepository incidentRepository;
    private final IncidentQueryRepository incidentQueryRepository;
    private final IncidentAssignmentRepository assignmentRepository;
    private final MaintenanceScheduleRepository maintenanceScheduleRepository;
    private final DomainEventPublisher eventPublisher;

    /**
     * 毎時0分（JST）に実行。
     * 1. SLA超過インシデントの検出とis_sla_breachedフラグ更新、イベント発行
     * 2. 定期メンテナンスの次回実行日到来分のインシデント自動生成
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "incident_sla_check", lockAtMostFor = "PT5M")
    @Transactional
    public void runSlaCheck() {
        log.info("インシデントSLAチェックバッチ開始");
        LocalDateTime now = LocalDateTime.now();

        int slaBreachedCount = processSlaCheck(now);
        int maintenanceCount = processMaintenanceSchedules(now);

        log.info("インシデントSLAチェックバッチ完了: slaBreached={}, maintenanceGenerated={}",
                slaBreachedCount, maintenanceCount);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * SLA超過インシデントを検出し、is_sla_breachedをtrueに更新してイベントを発行する。
     *
     * @param now 現在日時
     * @return 処理件数
     */
    private int processSlaCheck(LocalDateTime now) {
        // SLA期限超過かつis_sla_breached=falseのインシデントIDを取得
        List<Long> breachedIds = incidentQueryRepository.findBreachedIncidents(now);
        if (breachedIds.isEmpty()) {
            return 0;
        }

        // バッチでインシデントを取得
        List<IncidentEntity> breachedIncidents = incidentRepository.findAllById(breachedIds);

        // is_sla_breachedをtrueに更新
        List<IncidentEntity> toUpdate = new ArrayList<>();
        for (IncidentEntity incident : breachedIncidents) {
            if (!incident.getIsSlaBreached()) {
                incident.markSlaBreached();
                toUpdate.add(incident);
            }
        }
        incidentRepository.saveAll(toUpdate);

        // 各インシデントについてIncidentSlaBreachedEvent発行
        int count = 0;
        for (IncidentEntity incident : toUpdate) {
            try {
                // 担当者IDを取得（最初の担当者のuserId）
                Long assigneeId = assignmentRepository.findByIncidentId(incident.getId())
                        .stream()
                        .findFirst()
                        .map(a -> a.getUserId())
                        .orElse(null);

                eventPublisher.publish(new IncidentSlaBreachedEvent(
                        incident.getId(),
                        incident.getScopeType(),
                        incident.getScopeId(),
                        incident.getTitle(),
                        incident.getSlaDeadline(),
                        assigneeId
                ));
                count++;
            } catch (Exception e) {
                log.warn("SLA超過イベント発行失敗: incidentId={}", incident.getId(), e);
            }
        }
        log.info("SLA超過処理完了: {}件", count);
        return count;
    }

    /**
     * 実行日到来の定期メンテナンスのインシデントを自動生成する。
     * CronExpressionで次回実行時刻を確認し、前回実行から経過していたらIncidentを自動生成する。
     *
     * @param now 現在日時
     * @return 生成件数
     */
    private int processMaintenanceSchedules(LocalDateTime now) {
        LocalDate today = now.toLocalDate();

        // 次回実行日が本日以前かつ有効なスケジュールを取得
        List<MaintenanceScheduleEntity> dueSchedules =
                maintenanceScheduleRepository.findByNextExecutionDateLessThanEqualAndIsActiveTrueAndDeletedAtIsNull(today);

        int count = 0;
        for (MaintenanceScheduleEntity schedule : dueSchedules) {
            try {
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

                IncidentEntity savedIncident = incidentRepository.save(incident);

                // 次回実行日を更新
                LocalDate nextDate = calcNextExecutionDate(schedule.getCronExpression());
                schedule.updateNextExecutionDate(nextDate);
                maintenanceScheduleRepository.save(schedule);

                log.info("定期メンテナンスインシデント自動生成: scheduleId={}, incidentId={}",
                        schedule.getId(), savedIncident.getId());

                // IncidentReportedEvent発行
                eventPublisher.publish(new IncidentReportedEvent(
                        savedIncident.getId(),
                        savedIncident.getScopeType(),
                        savedIncident.getScopeId(),
                        savedIncident.getTitle(),
                        savedIncident.getPriority(),
                        savedIncident.getReportedBy()
                ));

                count++;
            } catch (Exception e) {
                log.warn("定期メンテナンス自動生成失敗: scheduleId={}", schedule.getId(), e);
            }
        }
        return count;
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
