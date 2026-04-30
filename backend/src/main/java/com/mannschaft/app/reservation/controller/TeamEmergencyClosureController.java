package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosureConfirmationResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosurePreviewResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.service.EmergencyClosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 臨時休業通知のプレビューを取得する。送信前に影響を受ける予約件数・詳細を確認できる。
     *
     * <p>{@code startTime} / {@code endTime} を両方指定すると部分時間帯休業のプレビューになる。
     * 省略した場合は終日休業として扱う。時刻フォーマットは {@code HH:mm}（時間単位、HH:00 のみ）。
     */
    @GetMapping("/preview")
    @Operation(summary = "臨時休業プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
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
     */
    @PostMapping
    @Operation(summary = "臨時休業通知送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<EmergencyClosureResponse>> sendClosure(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateEmergencyClosureRequest request) {
        Long operatorUserId = SecurityUtils.getCurrentUserId();
        EmergencyClosureResponse response =
                emergencyClosureService.sendClosure(teamId, operatorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チームの臨時休業通知履歴を取得する。
     */
    @GetMapping
    @Operation(summary = "臨時休業通知履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EmergencyClosureResponse>>> listClosures(
            @PathVariable Long teamId) {
        List<EmergencyClosureResponse> response = emergencyClosureService.listClosures(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 臨時休業通知を確認済みとしてマークする（患者側）。
     */
    @PostMapping("/{closureId}/confirm")
    @Operation(summary = "臨時休業確認")
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
    public ResponseEntity<ApiResponse<List<EmergencyClosureConfirmationResponse>>> getConfirmations(
            @PathVariable Long teamId,
            @PathVariable Long closureId) {
        List<EmergencyClosureConfirmationResponse> responses =
                emergencyClosureService.getConfirmations(teamId, closureId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
