package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleSource;
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予定の作成・更新・取消の Google カレンダーへの反映。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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

        // AC-12: GOOGLE_IMPORT ソースは Phase 3 自動同期をスキップ（無限ループ防止）
        if (ScheduleSource.GOOGLE_IMPORT.equals(schedule.getSource())) {
            log.debug("GOOGLE_IMPORT ソースのスケジュール {}: Google への再プッシュをスキップ", event.getScheduleId());
            return;
        }

        List<Long> targetUserIds = resolveTargetUserIds(event.getScopeType(), event.getScopeId());
        int pushed = 0;
        for (Long userId : targetUserIds) {
            // AC-3: 可視性・min_view_role を満たさないユーザーへは push しない。
            if (!googleCalendarService.isSchedulePushableToUser(schedule, userId)) {
                continue;
            }
            googleCalendarService.syncScheduleToGoogle(schedule, userId);
            pushed++;
        }
        log.info("Google Calendar同期（作成）完了: scheduleId={}, 対象ユーザー数={}, push数={}",
                event.getScheduleId(), targetUserIds.size(), pushed);
    }

    /**
     * スケジュール更新イベントを処理する。該当スコープの同期設定が有効なユーザーに対して
     * Googleカレンダーイベントを更新する。
     *
     * @param event スケジュール更新イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予定の作成・更新・取消の Google カレンダーへの反映。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onScheduleUpdated(ScheduleUpdatedEvent event) {
        log.info("スケジュール更新イベント受信: scheduleId={}", event.getScheduleId());

        ScheduleEntity schedule = scheduleRepository.findById(event.getScheduleId()).orElse(null);
        if (schedule == null) {
            log.warn("同期対象スケジュールが見つかりません: scheduleId={}", event.getScheduleId());
            return;
        }

        // AC-12: GOOGLE_IMPORT ソースは Phase 3 自動同期をスキップ（無限ループ防止）
        if (ScheduleSource.GOOGLE_IMPORT.equals(schedule.getSource())) {
            log.debug("GOOGLE_IMPORT ソースのスケジュール {}: Google への再プッシュをスキップ", event.getScheduleId());
            return;
        }

        String scopeType = resolveScopeType(schedule);
        Long scopeId = resolveScopeId(schedule);
        List<Long> targetUserIds = resolveTargetUserIds(scopeType, scopeId);
        int pushed = 0;
        for (Long userId : targetUserIds) {
            // 可視性・min_view_role を満たさないユーザーへは push しない（作成経路と同一ゲート）。
            if (!googleCalendarService.isSchedulePushableToUser(schedule, userId)) {
                continue;
            }
            googleCalendarService.syncScheduleToGoogle(schedule, userId);
            pushed++;
        }
        log.info("Google Calendar同期（更新）完了: scheduleId={}, 対象ユーザー数={}, push数={}",
                event.getScheduleId(), targetUserIds.size(), pushed);
    }

    /**
     * スケジュールキャンセルイベントを処理する。該当スコープの同期設定が有効なユーザーに対して
     * Googleカレンダーイベントのステータスを変更する。
     *
     * @param event スケジュールキャンセルイベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。予定の作成・更新・取消の Google カレンダーへの反映。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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
            // AC-8: キャンセルは update ではなく Google イベント削除（cancelled 化）で表現する。
            googleCalendarService.syncCancelledScheduleToGoogle(schedule.getId(), userId);
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
