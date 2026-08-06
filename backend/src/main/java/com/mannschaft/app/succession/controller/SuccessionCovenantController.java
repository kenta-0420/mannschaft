package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.succession.dto.SignCovenantRequest;
import com.mannschaft.app.succession.dto.SuccessionCovenantResponse;
import com.mannschaft.app.succession.service.SuccessionCovenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 入居時誓約コントローラー（F09.15 §6 入居時誓約 API）。
 *
 * <p>エンドポイント設計は設計書 §6 に準拠する:
 * <ul>
 *   <li>POST /api/v1/succession/covenants/sign — 発行 + 署名（本人）</li>
 *   <li>POST /api/v1/succession/covenants/{id}/revoke — 撤回（本人）</li>
 *   <li>GET /api/v1/succession/covenants/{id} — 詳細取得（本人 + ADMIN）</li>
 *   <li>GET /api/v1/succession/covenants/me — 本人の履歴</li>
 *   <li>GET /api/v1/organizations/{orgId}/succession/covenants — 組織内一覧（ADMIN のみ）</li>
 * </ul>
 *
 * <p>認可は Service 層の {@code AccessControlService} 判定に委譲し、本 Controller では
 * パスパラメータの組織 ID と現在ユーザーを引き渡すのみとする。
 */
@RestController
@Tag(name = "入居時誓約（F09.15）", description = "F09.15 居住者継承支援 - 入居時誓約 API")
@RequiredArgsConstructor
public class SuccessionCovenantController {

    private final SuccessionCovenantService covenantService;

    /**
     * 誓約発行 + 署名（一括処理）。本人が同意項目を確認して呼ぶ。
     *
     * <p>ステータス 201 Created で誓約レコードを返す。
     */
    // 認可根治済み（本監査で追加）: SuccessionCovenantService#signCovenant が
    // resident.getUserId().equals(currentUserId) でリクエストボディの residentRegistryId が
    // 呼び出し者本人の居住者台帳であることを検証する（他人の台帳を指定した代理署名・PII混入・
    // 多重署名ロックを防止。不一致時は RESIDENT_REGISTRY_NOT_FOUND で存在秘匿）。
    @AuthorizedInService
    @PostMapping("/api/v1/succession/covenants/sign")
    @Operation(summary = "入居時誓約への署名（PDF 生成 + 内部署名トークン付与 + 保存）",
            description = "本人が同意項目を確認した上で誓約 PDF を発行・署名・保存する一括処理。")
    public ResponseEntity<ApiResponse<SuccessionCovenantResponse>> signCovenant(
            @Valid @RequestBody SignCovenantRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        SuccessionCovenantResponse response = covenantService.signCovenant(request, currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 誓約撤回（本人のみ）。
     */
    // 認可根治済み: SuccessionCovenantService#revokeCovenant が
    // entity.getSignerUserId().equals(currentUserId) で本人所有を検証する（COVENANT_FORBIDDEN）。
    @AuthorizedInService
    @PostMapping("/api/v1/succession/covenants/{id}/revoke")
    @Operation(summary = "入居時誓約の撤回（本人のみ）")
    public ResponseEntity<ApiResponse<SuccessionCovenantResponse>> revokeCovenant(
            @PathVariable UUID id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(covenantService.revokeCovenant(id, currentUserId)));
    }

    /**
     * 誓約詳細取得（本人 + 組織 ADMIN）。
     *
     * @param orgId 組織 ID（テナント絞り込み）
     * @param id    誓約 ID
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/covenants/{id}")
    @Operation(summary = "誓約詳細取得（本人 + 組織 ADMIN）")
    public ResponseEntity<ApiResponse<SuccessionCovenantResponse>> getCovenant(
            @PathVariable Long orgId, @PathVariable UUID id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(covenantService.getCovenant(id, orgId, currentUserId)));
    }

    /**
     * 組織内の誓約一覧（組織 ADMIN のみ）。
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/covenants")
    @Operation(summary = "組織内の誓約一覧（組織 ADMIN のみ）")
    public ResponseEntity<ApiResponse<Page<SuccessionCovenantResponse>>> listOrgCovenants(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(ApiResponse.of(
                covenantService.listOrgCovenants(orgId, pageable, currentUserId)));
    }

    /**
     * 本人の誓約履歴。
     */
    @SelfScopedEndpoint("SuccessionCovenantService#listMyCovenants が "
            + "SecurityUtils.getCurrentUserId()（signerUserId）のみを検索条件に束縛する")
    @GetMapping("/api/v1/succession/covenants/me")
    @Operation(summary = "本人の誓約履歴（自分自身のみ）")
    public ResponseEntity<ApiResponse<List<SuccessionCovenantResponse>>> listMyCovenants() {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(covenantService.listMyCovenants(currentUserId)));
    }
}
