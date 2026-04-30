package com.mannschaft.app.proxy.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.dto.CreateProxyInputConsentRequest;
import com.mannschaft.app.proxy.dto.ProxyInputConsentResponse;
import com.mannschaft.app.proxy.dto.RevokeProxyInputConsentRequest;
import com.mannschaft.app.proxy.dto.ScanUploadUrlResponse;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 代理入力同意書管理コントローラー（F14.1 Phase 12-α）。
 * スマートフォン・PCを持たない住民への代理入力に関する同意書のCRUD・承認・撤回・S3 URL発行を提供する。
 */
@Tag(name = "代理入力", description = "F14.1 非デジタル住民対応・代理入力 同意書管理")
@RestController
@RequiredArgsConstructor
public class ProxyInputConsentController {

    private final ProxyInputConsentService consentService;

    /**
     * 同意書を登録する。
     * 権限: DEPUTY_ADMIN以上（AccessControlServiceでチェック）。
     * 登録後は別ADMINによる承認が必要（PENDING_APPROVALで返す）。
     */
    @Operation(summary = "代理入力同意書登録")
    @PostMapping("/api/v1/organizations/{orgId}/proxy-input-consents")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProxyInputConsentResponse> createConsent(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateProxyInputConsentRequest request) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        ProxyInputConsentEntity consent = consentService.createConsent(requestUserId, orgId, request.toCommand());
        return ApiResponse.of(ProxyInputConsentResponse.from(consent));
    }

    /**
     * 組合単位の同意書一覧を取得する。
     * 権限: ADMIN以上（AccessControlServiceでチェック）。
     */
    @Operation(summary = "代理入力同意書一覧（組合単位）")
    @GetMapping("/api/v1/organizations/{orgId}/proxy-input-consents")
    public ApiResponse<List<ProxyInputConsentResponse>> getConsentsByOrganization(
            @PathVariable Long orgId) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        List<ProxyInputConsentResponse> responses = consentService.getConsentsByOrganization(requestUserId, orgId)
                .stream()
                .map(ProxyInputConsentResponse::from)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * 自分が代理者として保有する有効同意書一覧を取得する（ProxyInputDeskView起動時）。
     * 権限: DEPUTY_ADMIN以上（自分自身のデータのみ）。
     */
    @Operation(summary = "自分の有効代理入力同意書一覧")
    @GetMapping("/api/v1/proxy-input-consents/active")
    public ApiResponse<List<ProxyInputConsentResponse>> getActiveConsents() {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        List<ProxyInputConsentResponse> responses = consentService.getActiveConsentsForProxy(requestUserId)
                .stream()
                .map(ProxyInputConsentResponse::from)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * 同意書を承認する。
     * 権限: PROXY_CONSENT_APPROVE 保有かつ proxy_user_id != 自分（自己承認禁止）。
     */
    @Operation(summary = "代理入力同意書承認")
    @PatchMapping("/api/v1/proxy-input-consents/{id}/approve")
    public ApiResponse<ProxyInputConsentResponse> approveConsent(@PathVariable Long id) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        ProxyInputConsentEntity consent = consentService.approveConsent(requestUserId, id);
        return ApiResponse.of(ProxyInputConsentResponse.from(consent));
    }

    /**
     * 同意書を撤回する。
     * 権限: 本人（subjectUserId）またはADMIN。
     */
    @Operation(summary = "代理入力同意書撤回")
    @PatchMapping("/api/v1/proxy-input-consents/{id}/revoke")
    public ApiResponse<Void> revokeConsent(
            @PathVariable Long id,
            @Valid @RequestBody RevokeProxyInputConsentRequest request) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        consentService.revokeConsent(requestUserId, id, request.toCommand());
        return ApiResponse.of(null);
    }

    /**
     * 代理入力履歴を取得する（監査用）。
     * 権限: ADMIN以上 or 本人（subjectUserId指定時）。
     * Phase 12-α では同意書ベースの一覧を返す。Phase 13-α で proxy_input_records の詳細一覧に拡張予定。
     */
    @Operation(summary = "代理入力履歴一覧（監査用）")
    @GetMapping("/api/v1/proxy-input-records")
    public ApiResponse<List<ProxyInputConsentResponse>> getProxyInputRecords(
            @RequestParam(required = false) Long subjectUserId) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        Long targetUserId = subjectUserId != null ? subjectUserId : requestUserId;
        List<ProxyInputConsentResponse> responses = consentService.getConsentsBySubject(requestUserId, targetUserId)
                .stream()
                .map(ProxyInputConsentResponse::from)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * スキャン文書アップロード用 presigned PUT URL を発行する（TTL 5分）。
     * クライアントはこのURLを使ってブラウザから直接S3にアップロードし、
     * 返却された s3Key を同意書登録API（POST /proxy-input-consents）に提出する。
     * 権限: DEPUTY_ADMIN以上。
     */
    @Operation(summary = "スキャン文書アップロードURL発行")
    @PostMapping("/api/v1/organizations/{orgId}/proxy-input-consents/scan-upload-url")
    public ApiResponse<ScanUploadUrlResponse> generateScanUploadUrl(@PathVariable Long orgId) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(ScanUploadUrlResponse.from(
                consentService.generateScanUploadUrl(requestUserId, orgId)));
    }

    /**
     * スキャン文書ダウンロード用 presigned GET URL を発行する（TTL 5分）。
     * 権限: ADMIN以上 or 本人（subjectUserId）。
     */
    @Operation(summary = "スキャン文書ダウンロードURL発行")
    @GetMapping("/api/v1/proxy-input-consents/{id}/scan-download-url")
    public ApiResponse<String> generateScanDownloadUrl(@PathVariable Long id) {
        Long requestUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(consentService.generateScanDownloadUrl(requestUserId, id));
    }
}
