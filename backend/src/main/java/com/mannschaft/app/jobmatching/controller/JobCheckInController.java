package com.mannschaft.app.jobmatching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.jobmatching.controller.dto.CheckInResponse;
import com.mannschaft.app.jobmatching.controller.dto.RecordCheckInRequest;
import com.mannschaft.app.jobmatching.service.JobCheckInService;
import com.mannschaft.app.jobmatching.service.command.CheckInCommand;
import com.mannschaft.app.jobmatching.service.command.CheckInResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * QR チェックイン／アウト記録コントローラー（F13.1 Phase 13.1.2）。
 *
 * <p>Worker がスキャンした QR トークン（または手動入力短コード）を受け取り、
 * {@link JobCheckInService} に委譲する。認可・重複検知・トークン検証・状態遷移・通知は
 * すべて Service 層で実施し、Controller は Request/Response 変換に徹する（薄く保つ方針）。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>{@code POST /api/v1/jobs/check-ins} — チェックイン／アウト記録</li>
 * </ul>
 *
 * <p>path は設計書と既存流儀の中間を取り {@code /api/v1/jobs/check-ins} とする。
 * 契約 ID は request body（{@code contractId}）で受け取り、認可は Service 側で Worker 本人性を確認する。</p>
 */
@RestController
@RequestMapping("/api/v1/jobs/check-ins")
@Tag(name = "QR チェックイン")
@RequiredArgsConstructor
@Validated
public class JobCheckInController {

    private final JobCheckInService checkInService;

    /**
     * QR チェックイン／アウトを記録する。
     *
     * <p>認可／重複／トークン検証はすべて Service 側で実施する。各種エラーは
     * {@code GlobalExceptionHandler} により以下へマッピングされる:</p>
     * <ul>
     *   <li>{@code JOB_QR_TOKEN_EXPIRED} / {@code JOB_QR_TOKEN_REUSED} /
     *       {@code JOB_QR_SHORT_CODE_NOT_FOUND} / {@code JOB_CHECK_IN_ALREADY_EXISTS} — 400 Bad Request</li>
     *   <li>{@code JOB_QR_TOKEN_INVALID_SIGNATURE} — 401 Unauthorized</li>
     *   <li>{@code JOB_QR_TOKEN_WRONG_WORKER} / {@code JOB_CHECK_IN_CONCURRENT_CONFLICT} — 403 Forbidden</li>
     *   <li>{@code JOB_CHECK_OUT_BEFORE_CHECK_IN} / {@code JOB_INVALID_STATE_TRANSITION} — 409 Conflict</li>
     *   <li>{@code JOB_CONTRACT_NOT_FOUND} — 404 Not Found</li>
     * </ul>
     *
     * @param req チェックイン／アウト記録リクエスト
     * @return 記録結果（生成 ID・遷移後ステータス・業務時間など）
     */
    @PostMapping
    @Operation(summary = "QR チェックイン／アウト記録",
            description = "Worker がスキャンした QR トークンまたは短コードで契約のチェックイン／アウトを記録する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "記録成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "トークン失効／再利用／重複等")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "トークン署名不正")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Worker 不一致／掛け持ち")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "契約が見つからない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "IN 未完で OUT を試行／不正な状態遷移")
    public ResponseEntity<ApiResponse<CheckInResponse>> recordCheckIn(
            @Valid @RequestBody RecordCheckInRequest req) {
        Long workerUserId = SecurityUtils.getCurrentUserId();

        CheckInCommand cmd = new CheckInCommand(
                req.contractId(),
                workerUserId,
                req.token(),
                req.shortCode(),
                req.type(),
                req.scannedAt(),
                req.offlineSubmitted(),
                req.manualCodeFallback(),
                req.geoLat(),
                req.geoLng(),
                req.geoAccuracy(),
                req.clientUserAgent()
        );
        CheckInResult result = checkInService.recordCheckIn(cmd);

        CheckInResponse body = new CheckInResponse(
                result.checkInId(),
                result.contractId(),
                result.type(),
                result.newStatus(),
                result.workDurationMinutes(),
                result.geoAnomaly()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(body));
    }
}
