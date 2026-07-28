package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.ExportRequestResponse;
import com.mannschaft.app.circulation.dto.ExportStatusResponse;
import com.mannschaft.app.circulation.service.CirculationExportService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 押印済み証跡 PDF エクスポートコントローラー（F05.2 Phase 11 第四陣 4-C）。
 *
 * <p>設計書: {@code docs/features/F05.2_circular.md} §4.8 / §残課題マトリクス</p>
 *
 * <p>提供する 2 エンドポイント:</p>
 * <ul>
 *   <li>{@code GET /api/v1/circulations/{documentId}/export} —
 *       生成済の場合 302 リダイレクト → R2 Pre-signed URL。
 *       未生成 / 失敗の場合は非同期ジョブを起動し 202 Accepted。
 *       生成中の場合は 202 Accepted（再起動なし）。</li>
 *   <li>{@code GET /api/v1/circulations/{documentId}/export/status} — 現在の生成状況を返却</li>
 * </ul>
 *
 * <p>認可（認可根治 Wave4 是正）: Service 層で「作成者 / 受信者 / 当該文書スコープの ADMIN」を
 * per-scope に判定する。旧実装は Controller が JWT の {@code ROLE_ADMIN}（スコープを問わない
 * 文字列一致）保有有無を判定して Service に渡し、無条件バイパスさせていたため、
 * どこか 1 つのチーム/組織で ADMIN であれば他団体の COMPLETED 回覧の押印済み証跡 PDF を
 * 無認可 DL できる BOLA だった。Controller はグローバル admin 判定を一切行わず、
 * {@code actorId} のみを Service に渡す。</p>
 */
@RestController
@RequestMapping("/api/v1/circulations/{documentId}/export")
@Tag(name = "回覧板（PDFエクスポート）", description = "F05.2 Phase 11 第四陣 4-C 押印済み証跡 PDF エクスポート")
@RequiredArgsConstructor
public class CirculationExportController {

    private final CirculationExportService exportService;

    /**
     * 押印済み証跡 PDF のエクスポートを要求する。
     */
    @GetMapping
    @Operation(summary = "押印済み証跡PDFエクスポート",
            description = "COMPLETED の回覧文書を PDF 化する。生成済の場合は 302 リダイレクト、未生成の場合は 202 Accepted で非同期ジョブを起動する")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "302", description = "生成済 PDF への Pre-signed URL リダイレクト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "非同期生成ジョブ受付")
    public ResponseEntity<?> requestExport(@PathVariable Long documentId) {
        Long actorId = SecurityUtils.getCurrentUserId();

        Object result = exportService.requestExport(documentId, actorId);

        // COMPLETED かつ URL 入りの場合は 302 リダイレクト
        if (result instanceof ExportStatusResponse status
                && "COMPLETED".equals(status.status())
                && status.url() != null) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(status.url()))
                    .build();
        }

        // それ以外（GENERATING / FAILED）は 202 Accepted で受付確認
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(result));
    }

    /**
     * 押印済み証跡 PDF の生成状況を確認する。
     */
    @GetMapping("/status")
    @Operation(summary = "押印済み証跡PDFエクスポート状況確認",
            description = "現在の生成状態（PENDING / COMPLETED / FAILED）を返却する。COMPLETED の場合は Pre-signed URL も含む")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ExportStatusResponse>> getStatus(@PathVariable Long documentId) {
        Long actorId = SecurityUtils.getCurrentUserId();

        ExportStatusResponse response = exportService.getExportStatus(documentId, actorId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
