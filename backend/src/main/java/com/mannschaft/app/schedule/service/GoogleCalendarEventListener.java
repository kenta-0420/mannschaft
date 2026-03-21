package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.event.ScheduleCancelledEvent;
import com.mannschaft.app.schedule.event.ScheduleCreatedEvent;
import com.mannschaft.app.schedule.event.ScheduleUpdatedEvent;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

        // TODO: 該当スコープの同期設定が有効なユーザーを取得
        // - syncSettingRepository.findEnabledUsersByScope(scopeType, scopeId)
        // - 各ユーザーに対して googleCalendarService.syncScheduleToGoogle() を呼び出し
        // - 個人スケジュールの場合は personalSyncEnabled=true のユーザーが対象
        log.info("Google Calendar同期（作成）仮実装: scheduleId={}", event.getScheduleId());
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

        // TODO: スケジュールのスコープを取得
        // - scheduleRepository.findById(scheduleId) でスケジュールのスコープを特定
        // - 該当スコープの同期設定が有効なユーザーを取得
        // - 各ユーザーに対して既存のGoogleイベントをPATCHで更新
        log.info("Google Calendar同期（更新）仮実装: scheduleId={}", event.getScheduleId());
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

        // TODO: スケジュールのスコープを取得
        // - 該当スコープの同期設定が有効なユーザーを取得
        // - 各ユーザーに対してGoogleカレンダーイベントをキャンセル状態に更新
        // - Google Calendar API: PATCH event with status="cancelled"
        log.info("Google Calendar同期（キャンセル）仮実装: scheduleId={}", event.getScheduleId());
    }
}
