package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.event.dto.DismissalReminderTargetResponse;
import com.mannschaft.app.event.dto.DismissalRequest;
import com.mannschaft.app.event.dto.DismissalStatusResponse;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.service.EventDismissalService;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * イベント解散通知コントローラー。F03.12 §16 / Phase11。
 *
 * <p>主催者がワンタップで全参加者・見守り者に解散通知を送る API と、
 * 解散通知の送信状態を確認する API、および主催イベントのうち未解散リマインダー対象を
 * 一覧取得する API を提供する。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>POST /api/v1/teams/{teamId}/events/{eventId}/dismissal — 解散通知送信（ADMIN/STAFF）</li>
 *   <li>GET  /api/v1/teams/{teamId}/events/{eventId}/dismissal/status — 解散状態確認</li>
 *   <li>GET  /api/v1/events/my-organizing/dismissal-reminders — 主催未解散イベント一覧（Phase11）</li>
 * </ul>
 *
 * <p><b>認可（認可根治 Wave3-B12event）:</b> {@link EventDismissalService#sendDismissalNotification} /
 * {@link EventDismissalService#getDismissalStatus} は {@code findByIdAndTeamScopeId} で
 * イベント帰属を検証するのに加え、Controller 入口で {@link EventScopeAccessGuard} により
 * 操作者がスコープメンバーであることを検証する（Service 側の帰属検証は二重防御として維持）。
 * {@code getMyDismissalReminderTargets} は Service 内で {@code createdBy=userId} のみを返す設計のため
 * スコープ侵害が起きず対象外（Javadoc 参照）。</p>
 */
@RestController
@Tag(name = "解散通知", description = "F03.12 §16 イベント解散通知API")
@RequiredArgsConstructor
public class EventDismissalController {

    private final EventDismissalService eventDismissalService;
    private final EventScopeAccessGuard eventScopeAccessGuard;

    /**
     * 解散通知を全参加者・見守り者に送信する。
     *
     * <p>チームの ADMIN または STAFF のみ操作可能。
     * 既に送信済みの場合は 409 Conflict を返す。</p>
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @param request 解散通知リクエスト（message・actualEndAt・notifyGuardians）
     * @return 201 Created
     */
    @PostMapping("/api/v1/teams/{teamId}/events/{eventId}/dismissal")
    @Operation(summary = "解散通知送信", description = "全参加者・見守り者に解散通知を送る（ADMIN/STAFF専用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "解散通知送信成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "既に解散通知済み")
    public ResponseEntity<ApiResponse<Void>> sendDismissal(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @Valid @RequestBody DismissalRequest request) {

        Long operatorUserId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireScopeAdmin(operatorUserId, EventScopeType.TEAM, teamId, eventId);
        eventDismissalService.sendDismissalNotification(eventId, teamId, operatorUserId, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(null));
    }

    /**
     * 解散通知の送信状態を取得する。
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @return 解散通知状態（dismissalNotificationSentAt・reminderCount・isDismissed 等）
     */
    @GetMapping("/api/v1/teams/{teamId}/events/{eventId}/dismissal/status")
    @Operation(summary = "解散通知状態確認", description = "解散通知の送信済み有無・リマインド回数を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DismissalStatusResponse>> getDismissalStatus(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {

        eventScopeAccessGuard.requireScopeMember(
                SecurityUtils.getCurrentUserId(), EventScopeType.TEAM, teamId, eventId);
        DismissalStatusResponse response = eventDismissalService.getDismissalStatus(eventId, teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ログインユーザーが主催している、終了予定時刻を過ぎたが未解散のチームイベント一覧を取得する。
     * F03.12 Phase11 / §16 Widget 連携。
     *
     * <p>ダッシュボード Widget {@code WidgetEventDismissalReminder} がカード描画用に呼び出す。
     * 認証済みユーザーであれば誰でも自身の主催イベントを取得可能（Service 層で createdBy = userId
     * のみが返るためスコープ侵害は発生しない）。</p>
     *
     * @return 主催未解散イベントのリスト（endAt 昇順）
     */
    @SelfScopedEndpoint(
            "EventDismissalService#getMyDismissalReminderTargets は createdBy=userId のみを"
                    + "検索条件に渡す（EventDismissalController#getMyDismissalReminderTargets）")
    @GetMapping("/api/v1/events/my-organizing/dismissal-reminders")
    @Operation(summary = "主催未解散イベント一覧",
            description = "ログインユーザー主催・終了済み・未解散のチームイベントを返す（Widget 用）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<DismissalReminderTargetResponse>>> getMyDismissalReminderTargets() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<DismissalReminderTargetResponse> response =
                eventDismissalService.getMyDismissalReminderTargets(currentUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
