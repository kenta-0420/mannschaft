package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.event.dto.RollCallCandidateResponse;
import com.mannschaft.app.event.dto.RollCallEntryRequest;
import com.mannschaft.app.event.dto.RollCallSessionRequest;
import com.mannschaft.app.event.dto.RollCallSessionResponse;
import com.mannschaft.app.event.service.EventRollCallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主催者点呼コントローラー。F03.12 §14 主催者点呼（一括チェックイン）APIを提供する。
 *
 * <p>スマホを持たない子供・高齢者向けに、チームの ADMIN/STAFF が点呼形式で
 * 参加予定者の出欠を一括記録し、ケア対象者の保護者へ自動通知する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/events/{eventId}/roll-call")
@Tag(name = "主催者点呼", description = "F03.12 §14 主催者点呼（一括チェックイン）機能")
@RequiredArgsConstructor
public class EventRollCallController {

    private final EventRollCallService rollCallService;

    /**
     * 点呼候補者一覧を取得する。
     *
     * <p>RSVP=ATTENDING/MAYBE の参加予定者を返す。
     * 各候補者にケア対象フラグ・見守り者数・既存チェックイン状態が付与される。</p>
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @return 点呼候補者レスポンスリスト
     */
    @GetMapping("/candidates")
    @Operation(summary = "点呼候補者一覧取得",
               description = "RSVP=ATTENDING/MAYBEの参加予定者一覧。ケア対象フラグ・保護者数・既チェックイン状態を含む。権限: ADMIN/STAFF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<RollCallCandidateResponse>>> getCandidates(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        List<RollCallCandidateResponse> candidates =
                rollCallService.getRollCallCandidates(eventId, teamId, operatorUserId);
        return ResponseEntity.ok(ApiResponse.of(candidates));
    }

    /**
     * 点呼セッションを一括登録する。
     *
     * <p>1 トランザクションで複数メンバーの出欠を記録する。
     * rollCallSessionId を冪等キーとして使用するため、再送時は既存レコードを上書きする。
     * ケア対象者（isUnderCare=true）かつ PRESENT の場合、保護者へ即時通知が送信される（notifyGuardiansImmediately=true 時）。</p>
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @param request 点呼セッションリクエスト
     * @return 点呼セッション処理結果サマリ
     */
    @PostMapping
    @Operation(summary = "点呼セッション一括登録",
               description = "PRESENT/ABSENT/LATE を一括記録。ケア対象+PRESENT の場合は保護者通知を送信。冪等キー: rollCallSessionId。権限: ADMIN/STAFF")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "登録成功")
    public ResponseEntity<ApiResponse<RollCallSessionResponse>> submitRollCall(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @Valid @RequestBody RollCallSessionRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        RollCallSessionResponse response =
                rollCallService.submitRollCall(eventId, teamId, operatorUserId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 過去の点呼セッション一覧を取得する（主催者向け履歴）。
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @return 点呼セッションIDリスト
     */
    @GetMapping("/sessions")
    @Operation(summary = "点呼セッション履歴一覧",
               description = "過去に実施した点呼セッションのIDリストを返す。権限: ADMIN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<String>>> getSessions(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        List<String> sessions = rollCallService.getRollCallSessions(eventId);
        return ResponseEntity.ok(ApiResponse.of(sessions));
    }

    /**
     * 点呼結果を個別修正する（誤チェックの訂正）。
     *
     * <p>既送信の保護者通知タイムスタンプは引き継ぎ、重複通知を抑止する。</p>
     *
     * @param teamId  チームID
     * @param eventId イベントID
     * @param userId  修正対象ユーザーID
     * @param entry   修正内容
     * @return 204 No Content
     */
    @PatchMapping("/{userId}")
    @Operation(summary = "点呼結果個別修正",
               description = "誤チェックの訂正。既送信保護者通知は再送しない。権限: ADMIN")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "修正成功")
    public ResponseEntity<Void> patchRollCallEntry(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @PathVariable Long userId,
            @Valid @RequestBody RollCallEntryRequest entry) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        rollCallService.patchRollCallEntry(eventId, userId, entry, operatorUserId);
        return ResponseEntity.noContent().build();
    }
}
