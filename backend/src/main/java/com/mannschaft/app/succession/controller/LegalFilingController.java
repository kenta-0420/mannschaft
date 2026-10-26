package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.succession.dto.CreateLegalFilingRequest;
import com.mannschaft.app.succession.dto.EvidenceDownloadUrlResponse;
import com.mannschaft.app.succession.dto.LegalFilingResponse;
import com.mannschaft.app.succession.entity.LegalFilingEntity;
import com.mannschaft.app.succession.service.LegalFilingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 法的手続き準備コントローラー（F09.15 S6-B）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.8〜§5.13
 *
 * <p>提供する操作:
 * <ul>
 *   <li>UC-C1: 法的手続きレコード起票（申立書テンプレート PDF 生成）</li>
 *   <li>UC-C2: 区分所有法 8 条 証拠パッケージ ZIP 生成</li>
 *   <li>証拠 ZIP の Pre-signed ダウンロード URL 発行（有効期間 1h）</li>
 *   <li>法的手続き一覧・詳細・居住者別履歴の取得</li>
 * </ul>
 *
 * <p>すべての操作は ADMIN 権限以上のユーザーのみ実行可能（Service 層の {@code checkAdminOrAbove} で認可）。
 * 認証ユーザー ID は {@link SecurityUtils#getCurrentUserId()} 経由で取得し、認可判定のため Service に渡す。
 */
@RestController
@Tag(name = "法的手続き準備（F09.15）", description = "F09.15 居住者継承支援 - 法的手続き準備 API")
@RequiredArgsConstructor
public class LegalFilingController {

    private final LegalFilingService legalFilingService;

    /**
     * 組織内の法的手続き一覧を取得する（ADMIN 以上）。
     *
     * @param orgId テナント組織 ID
     * @return 200 OK + 法的手続き一覧（作成日降順）
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/legal-filings")
    @Operation(
            summary = "法的手続き一覧（ADMIN 以上）",
            description = "組織内の法的手続きレコード一覧を返す。"
    )
    public ResponseEntity<ApiResponse<List<LegalFilingResponse>>> listByOrganization(
            @PathVariable Long orgId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        List<LegalFilingResponse> responses = legalFilingService.listByOrganization(orgId, requestingUserId).stream()
                .map(LegalFilingResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 居住者別の法的手続き履歴を取得する（ADMIN 以上）。
     *
     * @param orgId              テナント組織 ID
     * @param residentRegistryId 居住者台帳 ID
     * @return 200 OK + 法的手続き履歴（作成日降順）
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/legal-filings/by-resident/{residentRegistryId}")
    @Operation(
            summary = "居住者別法的手続き履歴（ADMIN 以上）",
            description = "指定した居住者台帳 ID に紐づく法的手続き履歴を返す。"
    )
    public ResponseEntity<ApiResponse<List<LegalFilingResponse>>> listByResident(
            @PathVariable Long orgId,
            @PathVariable Long residentRegistryId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        List<LegalFilingResponse> responses = legalFilingService
                .listByResident(residentRegistryId, orgId, requestingUserId).stream()
                .map(LegalFilingResponse::fromEntity)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 法的手続きレコードを起票する（ADMIN 以上）。
     *
     * <p>申立種別に応じた申立書テンプレート PDF を生成し、S3 にアップロードする。
     *
     * @param orgId   テナント組織 ID
     * @param request 起票リクエスト（居住者・居室・申立種別・備考）
     * @return 200 OK + 起票された法的手続きレコード
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/legal-filings")
    @Operation(
            summary = "法的手続き起票（ADMIN 以上）",
            description = "申立書テンプレート PDF を生成し、法的手続きレコードを作成する。"
    )
    public ResponseEntity<ApiResponse<LegalFilingResponse>> createLegalFiling(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateLegalFilingRequest request) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        LegalFilingEntity entity = legalFilingService.createLegalFiling(
                orgId,
                request.getResidentRegistryId(),
                request.getDwellingUnitId(),
                request.getFilingType(),
                request.getNote(),
                requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(LegalFilingResponse.fromEntity(entity)));
    }

    /**
     * 法的手続き詳細を取得する（ADMIN 以上）。
     *
     * @param orgId          テナント組織 ID
     * @param legalFilingId  法的手続きレコード ID（UUID）
     * @return 200 OK + 法的手続き詳細
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}")
    @Operation(
            summary = "法的手続き詳細取得（ADMIN 以上）",
            description = "指定した legalFilingId の法的手続きレコードを取得する。"
    )
    public ResponseEntity<ApiResponse<LegalFilingResponse>> getById(
            @PathVariable Long orgId,
            @PathVariable UUID legalFilingId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        LegalFilingEntity entity = legalFilingService.getById(legalFilingId, orgId, requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(LegalFilingResponse.fromEntity(entity)));
    }

    /**
     * 区分所有法 8 条 証拠パッケージ ZIP を生成する（ADMIN のみ）。
     *
     * <p>申立書テンプレート + エスカレーションタイムライン + 表紙の 3 つの PDF を
     * ZIP に格納し、S3 にアップロードして SHA-256 を記録する。
     *
     * @param orgId         テナント組織 ID
     * @param legalFilingId 法的手続きレコード ID（UUID）
     * @return 200 OK + 更新された法的手続きレコード（evidence_* がセット済）
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}/evidence-package")
    @Operation(
            summary = "証拠 ZIP 生成（ADMIN のみ）",
            description = "区分所有法 8 条 証拠パッケージ（申立書 + タイムライン + 表紙の ZIP）を生成し S3 アップロードする。"
    )
    public ResponseEntity<ApiResponse<LegalFilingResponse>> buildEvidencePackage(
            @PathVariable Long orgId,
            @PathVariable UUID legalFilingId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        LegalFilingEntity entity = legalFilingService.buildEvidencePackage(legalFilingId, orgId, requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(LegalFilingResponse.fromEntity(entity)));
    }

    /**
     * 証拠 ZIP の Pre-signed ダウンロード URL を発行する（ADMIN のみ・有効期間 1h）。
     *
     * @param orgId         テナント組織 ID
     * @param legalFilingId 法的手続きレコード ID（UUID）
     * @return 200 OK + Pre-signed URL と有効期間（秒）
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/legal-filings/{legalFilingId}/evidence-package/download-url")
    @Operation(
            summary = "証拠 ZIP ダウンロード URL（ADMIN のみ・1h 有効）",
            description = "証拠 ZIP の S3 Pre-signed ダウンロード URL を発行する。有効期間は 3600 秒。"
    )
    public ResponseEntity<ApiResponse<EvidenceDownloadUrlResponse>> getEvidenceDownloadUrl(
            @PathVariable Long orgId,
            @PathVariable UUID legalFilingId) {
        Long requestingUserId = SecurityUtils.getCurrentUserId();
        String url = legalFilingService.generateEvidenceDownloadUrl(legalFilingId, orgId, requestingUserId);
        return ResponseEntity.ok(ApiResponse.of(EvidenceDownloadUrlResponse.builder()
                .downloadUrl(url)
                .ttlSeconds(3600)
                .build()));
    }
}
