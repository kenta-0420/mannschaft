package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Google Calendarイベントリスナー。スケジュールの作成・更新・キャンセルイベントを受信し、
 * 該当スコープの同期設定を持つユーザーに対してGoogleカレンダーイベントを同期する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCalendarEventListener {

    private final GoogleCalendarService googleCalendarService;
    private final UserCalendarSyncSettingRepository syncSettingRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserGoogleCalendarConnectionRepository connectionRepository;

    /**
     * スケジュール作成イベントを処理する。該当スコープの同期設定が有効なユーザーに対して
     * Googleカレンダーイベントを作成する。
     *
     * @param event スケジュール作成イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onScheduleCreated(ScheduleCreatedEvent event) {
        log.info("スケジュール作成イベント受信: scheduleId={}, scope={}:{}",
                event.getScheduleId(), event.getScopeType(), event.getScopeId());

        ScheduleEntity schedule = scheduleRepository.findById(event.getScheduleId()).orElse(null);
        if (schedule == null) {
            log.warn("同期対象スケジュールが見つかりません: scheduleId={}", event.getScheduleId());
            return;
        }

        List<Long> targetUserIds = resolveTargetUserIds(event.getScopeType(), event.getScopeId());
        for (Long userId : targetUserIds) {
            googleCalendarService.syncScheduleToGoogle(schedule, userId);
        }
        log.info("Google Calendar同期（作成）完了: scheduleId={}, 対象ユーザー数={}", event.getScheduleId(), targetUserIds.size());
    }

    /**
     * スケジュール更新イベントを処理する。該当スコープの同期設定が有効なユーザーに対して
     * Googleカレンダーイベントを更新する。
     *
     * @param event スケジュール更新イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onScheduleUpdated(ScheduleUpdatedEvent event) {
        log.info("スケジュール更新イベント受信: scheduleId={}", event.getScheduleId());

        ScheduleEntity schedule = scheduleRepository.findById(event.getScheduleId()).orElse(null);
        if (schedule == null) {
            log.warn("同期対象スケジュールが見つかりません: scheduleId={}", event.getScheduleId());
            return;
        }

        String scopeType = resolveScopeType(schedule);
        Long scopeId = resolveScopeId(schedule);
        List<Long> targetUserIds = resolveTargetUserIds(scopeType, scopeId);
        for (Long userId : targetUserIds) {
            googleCalendarService.syncScheduleToGoogle(schedule, userId);
        }
        log.info("Google Calendar同期（更新）完了: scheduleId={}, 対象ユーザー数={}", event.getScheduleId(), targetUserIds.size());
    }

    /**
     * スケジュールキャンセルイベントを処理する。該当スコープの同期設定が有効なユーザーに対して
     * Googleカレンダーイベントのステータスを変更する。
     *
     * @param event スケジュールキャンセルイベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onScheduleCancelled(ScheduleCancelledEvent event) {
        log.info("スケジュールキャンセルイベント受信: scheduleId={}", event.getScheduleId());

        ScheduleEntity schedule = scheduleRepository.findById(event.getScheduleId()).orElse(null);
        if (schedule == null) {
            log.warn("同期対象スケジュールが見つかりません: scheduleId={}", event.getScheduleId());
            return;
        }

        String scopeType = resolveScopeType(schedule);
        Long scopeId = resolveScopeId(schedule);
        List<Long> targetUserIds = resolveTargetUserIds(scopeType, scopeId);
        for (Long userId : targetUserIds) {
            googleCalendarService.syncScheduleToGoogle(schedule, userId);
        }
        log.info("Google Calendar同期（キャンセル）完了: scheduleId={}, 対象ユーザー数={}", event.getScheduleId(), targetUserIds.size());
    }

    private List<Long> resolveTargetUserIds(String scopeType, Long scopeId) {
        if ("PERSONAL".equals(scopeType)) {
            return connectionRepository.findByUserIdAndIsActiveTrue(scopeId)
                    .filter(conn -> conn.getPersonalSyncEnabled())
                    .map(conn -> List.of(scopeId))
                    .orElse(List.of());
        }
        return syncSettingRepository.findByScopeTypeAndScopeIdAndIsEnabledTrue(scopeType, scopeId)
                .stream()
                .map(UserCalendarSyncSettingEntity::getUserId)
                .toList();
    }

    private String resolveScopeType(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) return "TEAM";
        if (schedule.isOrganizationScope()) return "ORGANIZATION";
        return "PERSONAL";
    }

    private Long resolveScopeId(ScheduleEntity schedule) {
        if (schedule.isTeamScope()) return schedule.getTeamId();
        if (schedule.isOrganizationScope()) return schedule.getOrganizationId();
        return schedule.getUserId();
    }
}
