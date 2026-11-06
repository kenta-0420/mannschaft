package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosureConfirmationResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosurePreviewResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.service.EmergencyClosureSendService;
import com.mannschaft.app.reservation.service.EmergencyClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 臨時休業一括通知コントローラー。F03.4+ 臨時休業期間の予約者への一括通知・キャンセルAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/emergency-closures")
@Tag(name = "臨時休業通知", description = "F03.4+ 臨時休業一括通知")
@RequiredArgsConstructor
public class TeamEmergencyClosureController {

    private final EmergencyClosureService emergencyClosureService;
    private final EmergencyClosureSendService emergencyClosureSendService;

    /**
     * 臨時休業通知のプレビューを取得する。送信前に影響を受ける予約件数・詳細を確認できる。
     *
     * <p>{@code startTime} / {@code endTime} を両方指定すると部分時間帯休業のプレビューになる。
     * 省略した場合は終日休業として扱う。時刻フォーマットは {@code HH:mm}（時間単位、HH:00 のみ）。
     */
    @GetMapping("/preview")
    @Operation(summary = "臨時休業プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<EmergencyClosurePreviewResponse>> previewClosure(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "HH:mm") LocalTime endTime) {
        EmergencyClosurePreviewResponse response =
                emergencyClosureService.previewClosure(teamId, startDate, endDate, startTime, endTime);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 臨時休業通知を送信する。対象期間の予約者にメールを一括送信し、必要に応じて予約をキャンセルする。
     *
     * <p>CMP-261104-0334: {@code Idempotency-Key} ヘッダ（UUID・任意）を付けると、同じキーの再送は新規作成せず
     * 先の結果を 200 で返す（連打・応答消失後の再送で多重送信しない）。同じキーで内容が違えば 409、
     * キーの形式不正は 400。ヘッダ無しは従来どおり毎回新規作成する（201）。キーはチーム×送信者で区別する。</p>
     */
    @PostMapping
    @Operation(summary = "臨時休業通知送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "同じ Idempotency-Key・同じ内容の再送。新規作成せず先の送信結果を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
            description = "同じ Idempotency-Key で内容が異なる（RESERVATION_055）",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = com.mannschaft.app.common.ErrorResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Idempotency-Key の形式が不正（RESERVATION_056）",
            content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @io.swagger.v3.oas.annotations.media.Schema(
                            implementation = com.mannschaft.app.common.ErrorResponse.class)))
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<EmergencyClosureResponse>> sendClosure(
            @PathVariable Long teamId,
            @Parameter(description = "送信の冪等キー（UUID）。同じ送信者・同じチームで同じキーの再送は先の結果を返す。"
                    + "形式不正は 400（RESERVATION_056）")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateEmergencyClosureRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        EmergencyClosureSendService.Outcome outcome =
                emergencyClosureSendService.send(teamId, operatorUserId, request, idempotencyKey);
        return ResponseEntity.status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.of(outcome.response()));
    }

    /**
     * チームの臨時休業通知履歴を取得する。
     */
    @GetMapping
    @Operation(summary = "臨時休業通知履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<List<EmergencyClosureResponse>>> listClosures(
            @PathVariable Long teamId) {
        List<EmergencyClosureResponse> response = emergencyClosureService.listClosures(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 臨時休業通知を確認済みとしてマークする（予約者側）。
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * パス変数 {@code teamId} はそれ単体ではスコープ判定に用いない。認可の実体は
     * {@code EmergencyClosureService#confirmClosure(Long, Long)} が
     * {@code EmergencyClosureConfirmationRepository#findByEmergencyClosureIdAndUserId} で
     * <b>呼び出しユーザー自身の確認レコードのみ</b>を特定し、該当しなければ
     * {@code CLOSURE_CONFIRMATION_NOT_FOUND} を投げる構造にある。確認レコードは
     * {@code sendClosure} が対象予約者にのみ作成するため、他人の確認を横取りすることは構造上できない
     * 自己スコープ EP であり、権限昇格は発生しない。データ依存でない構造的な自己スコープ認可のため
     * 白名簿クラス呼び出しを持たず、本マーカーで監査済であることを明示する。</p>
     */
    @PostMapping("/{closureId}/confirm")
    @Operation(summary = "臨時休業確認")
    @AuthorizedInService
    public ResponseEntity<Void> confirmClosure(
            @PathVariable Long teamId,
            @PathVariable Long closureId) {
        emergencyClosureService.confirmClosure(closureId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok().build();
    }

    /**
     * 確認状況一覧を取得する（送信者側）。
     */
    @GetMapping("/{closureId}/confirmations")
    @Operation(summary = "確認状況一覧")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<List<EmergencyClosureConfirmationResponse>>> getConfirmations(
            @PathVariable Long teamId,
            @PathVariable Long closureId) {
        List<EmergencyClosureConfirmationResponse> responses =
                emergencyClosureService.getConfirmations(teamId, closureId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
