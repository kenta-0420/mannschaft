package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatusResponse;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchRequest;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchResponse;
import com.mannschaft.app.circulation.dto.RemindResponse;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回覧文書管理者向けコントローラー（Phase 11 第三陣 3-A）。
 *
 * <p>F05.2 §4.7（管理者向け）および §4.8（状況可視化）に該当する 5 エンドポイントを提供する：</p>
 * <ul>
 *   <li>{@code POST /circulation-documents/{id}/force-complete} - 強制完了</li>
 *   <li>{@code POST /circulation-documents/force-complete/batch} - 一括強制完了（最大 20 件）</li>
 *   <li>{@code POST /circulation-documents/{id}/remind} - 手動リマインド</li>
 *   <li>{@code POST /circulation-documents/{id}/duplicate} - 複製</li>
 *   <li>{@code GET /circulation-documents/{id}/status} - 受信者ごと押印状況一覧（UI ブロッカー）</li>
 * </ul>
 *
 * <p>パス命名は設計書 §4.7-4.8 の {@code /api/v1/circulations/...} とは別構成だが、
 * Phase 11 第三陣 3-A タスク指定の通り {@code /api/v1/circulation-documents/...} を採用する。</p>
 *
 * <p><b>認可（Phase 11 事後検分 fixup / 2026-05-17）:</b>
 * 各エンドポイントに {@code @PreAuthorize} を宣言する。設計書 §4.7-4.8 / §8 の認可方針に従う:
 * <ul>
 *   <li>force-complete (単体・一括): ADMIN のみ</li>
 *   <li>remind: 作成者 / ADMIN / DEPUTY_ADMIN (MANAGE_CONTENT) → 当面 ADMIN で安全側、緩和は将来軍議</li>
 *   <li>duplicate: 作成者本人 / ADMIN / DEPUTY_ADMIN → 当面 ADMIN で安全側、緩和は将来軍議</li>
 *   <li>status: 回覧先メンバー / 作成者 / ADMIN / SYSTEM_ADMIN → 当面 ADMIN で安全側、緩和は将来軍議</li>
 * </ul>
 *
 * <p><b>真の認可強制点（認可根治 Phase 3-b / 2026-05-30）:</b>
 * 旧 {@code @PreAuthorize("hasRole('ADMIN')")} は {@code @EnableMethodSecurity} 点火時に JWT へ
 * ROLE_ADMIN が乗らず一斉 403 となるため、宣言を {@code isAuthenticated()} に是正した。
 * 本コントローラーの 5 EP の scope は <b>パス変数でなく文書エンティティ由来</b>（{@code documentId} から
 * 文書を引いて scopeType/scopeId を解決）であり、SpEL からパス変数で scope を参照できないため SpEL ガード化
 * できない。よって宣言は {@code isAuthenticated()} とし、真の per-scope 認可は
 * 本コントローラーの 5 エンドポイントが収束する {@link CirculationService} の各メソッド
 * （{@code forceCompleteDocument} / {@code forceCompleteBatch} / {@code remindDocument} /
 * {@code duplicateDocument} / {@code getDocumentStatus}）の処理本体前で、対象文書のスコープに対する
 * per-scope 認可（{@code AccessControlService} による ADMIN/DEPUTY_ADMIN 必須、SYSTEM_ADMIN は全許可）を
 * 実施する。scopeId は文書エンティティ由来で解決するため、別スコープの文書を操作する IDOR を防ぐ。</p>
 */
@RestController
@RequestMapping("/api/v1/circulation-documents")
@Tag(name = "回覧板（管理）", description = "F05.2 管理者向け操作と状況可視化（Phase 11 第三陣 3-A）")
@RequiredArgsConstructor
public class CirculationAdminController {

    private final CirculationService circulationService;

    /**
     * 回覧文書を強制完了する。
     */
    @PostMapping("/{documentId}/force-complete")
    @Operation(summary = "回覧文書強制完了", description = "全受信者が未押印でも管理者判断で完了扱いとする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "強制完了成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DocumentResponse>> forceComplete(
            @PathVariable Long documentId) {
        DocumentResponse response = circulationService.forceCompleteDocument(
                documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書を一括強制完了する。
     */
    @PostMapping("/force-complete/batch")
    @Operation(summary = "回覧文書一括強制完了", description = "最大 20 件まで一括で強制完了する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括処理完了（部分成功を含む）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ForceCompleteBatchResponse>> forceCompleteBatch(
            @Valid @RequestBody ForceCompleteBatchRequest request) {
        ForceCompleteBatchResponse response = circulationService.forceCompleteBatch(
                request.getDocumentIds(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 未押印者へ手動リマインドを送信する。
     */
    @PostMapping("/{documentId}/remind")
    @Operation(summary = "回覧文書手動リマインド", description = "未押印の受信者全員に通知を発火する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RemindResponse>> remind(
            @PathVariable Long documentId) {
        RemindResponse response = circulationService.remindDocument(
                documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書を複製する。
     */
    @PostMapping("/{documentId}/duplicate")
    @Operation(summary = "回覧文書複製", description = "受信者をコピーした新規 DRAFT 文書を作成する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DocumentResponse>> duplicate(
            @PathVariable Long documentId) {
        DocumentResponse response = circulationService.duplicateDocument(
                documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 受信者ごとの押印状況一覧を取得する（UI ブロッカー）。
     */
    @GetMapping("/{documentId}/status")
    @Operation(summary = "回覧文書受信者押印状況一覧", description = "詳細画面の主要表示要素")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DocumentStatusResponse>> getStatus(
            @PathVariable Long documentId) {
        DocumentStatusResponse response = circulationService.getDocumentStatus(
                documentId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
