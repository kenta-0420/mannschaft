package com.mannschaft.app.jobmatching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.jobmatching.controller.dto.IssueQrTokenRequest;
import com.mannschaft.app.jobmatching.controller.dto.QrTokenResponse;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobQrTokenEntity;
import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import com.mannschaft.app.jobmatching.policy.JobPolicy;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.service.JobQrTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * QR トークン発行／取得コントローラー（F13.1 Phase 13.1.2）。
 *
 * <p>Requester 側デバイスで QR コード画像化するための短命トークン発行と、
 * 画面再表示時の現在有効トークン取得を提供する。</p>
 *
 * <p>エンドポイント:</p>
 * <ul>
 *   <li>{@code POST /api/v1/contracts/{contractId}/qr-tokens} — 新規発行</li>
 *   <li>{@code GET  /api/v1/contracts/{contractId}/qr-tokens/current?type=IN|OUT} —
 *       現在有効な未消費トークン取得（存在しなければ 204 No Content）</li>
 * </ul>
 *
 * <p>path 規約は既存 {@link JobContractController}（{@code /api/v1/contracts/{id}/...}）に
 * 揃えている。認可は Service 層（{@link JobQrTokenService}）で実施するが、Controller でも
 * {@link JobPolicy#canIssueQrToken} で二重防御し、早期拒否による攻撃者への情報量を絞る。</p>
 */
@RestController
@RequestMapping("/api/v1/contracts/{contractId}/qr-tokens")
@Tag(name = "QR トークン")
@RequiredArgsConstructor
@Validated
public class JobQrTokenController {

    private final JobQrTokenService qrTokenService;
    private final JobContractRepository contractRepository;
    private final JobPolicy jobPolicy;

    /**
     * QR トークンを発行する（Requester 本人のみ）。
     *
     * @param contractId 対象契約 ID
     * @param req        IN/OUT 種別と任意 TTL（秒）
     * @return 発行結果（JWT 文字列 + 短コード + メタ情報）
     */
    @PostMapping
    @Operation(summary = "QR トークン発行", description = "Requester デバイスで QR 画像化するための短命トークンを発行する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "発行権限なし（Requester 本人以外）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "契約が見つからない")
    public ResponseEntity<ApiResponse<QrTokenResponse>> issue(
            @PathVariable Long contractId,
            @Valid @RequestBody IssueQrTokenRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();

        // Controller 側でも認可の早期チェック（Service 内でも同等判定が行われる）。
        JobContractEntity contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        if (!jobPolicy.canIssueQrToken(contract, userId)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        JobQrTokenService.IssueResult result =
                qrTokenService.issue(contractId, req.type(), req.ttlSeconds(), userId);

        QrTokenResponse body = new QrTokenResponse(
                result.token(),
                result.shortCode(),
                result.type(),
                result.issuedAt(),
                result.expiresAt(),
                result.kid()
        );
        return ResponseEntity.ok(ApiResponse.of(body));
    }

    /**
     * 現在有効な未消費トークンを取得する（Requester 画面再表示用）。
     *
     * <p>{@code token}（JWT 文字列）は DB に保存されていないため本エンドポイントの戻り値では
     * 常に {@code null} となる。クライアントは {@code shortCode} と {@code expiresAt} をもとに
     * 表示を継続するか、必要なら {@link #issue} を呼んで新規発行する。</p>
     *
     * @param contractId 対象契約 ID
     * @param type       IN / OUT 種別
     * @return 現在有効なトークン（存在しなければ 204 No Content）
     */
    @GetMapping("/current")
    @Operation(summary = "現在有効な QR トークン取得", description = "Requester 画面再表示用。存在しなければ 204 No Content")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "該当トークン無し")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "閲覧権限なし")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "契約が見つからない")
    public ResponseEntity<ApiResponse<QrTokenResponse>> getCurrent(
            @PathVariable Long contractId,
            @RequestParam JobCheckInType type) {
        Long userId = SecurityUtils.getCurrentUserId();

        // Controller 側でも認可の早期チェック。
        JobContractEntity contract = contractRepository.findById(contractId)
                .orElseThrow(() -> new BusinessException(JobmatchingErrorCode.JOB_CONTRACT_NOT_FOUND));
        if (!jobPolicy.canIssueQrToken(contract, userId)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_PERMISSION_DENIED);
        }

        Optional<JobQrTokenEntity> found = qrTokenService.getCurrent(contractId, type, userId);
        if (found.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        JobQrTokenEntity entity = found.get();
        // JWT 文字列は DB 非保存のため null。クライアントは shortCode 中心の運用とする。
        QrTokenResponse body = new QrTokenResponse(
                null,
                entity.getShortCode(),
                entity.getType(),
                entity.getIssuedAt(),
                entity.getExpiresAt(),
                entity.getKid()
        );
        return ResponseEntity.ok(ApiResponse.of(body));
    }
}
