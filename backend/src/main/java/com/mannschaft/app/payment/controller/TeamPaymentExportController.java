package com.mannschaft.app.payment.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.service.PaymentCsvExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * F08.9 P8: チーム支払い明細 CSV エクスポートコントローラー。
 *
 * <p>{@code GET /api/v1/teams/{teamId}/payment-items/{itemId}/payments/export} —
 * チーム ADMIN が支払い明細を UTF-8 BOM + CRLF 形式の CSV としてダウンロードする。</p>
 *
 * <p>認可: チーム ADMIN（{@link AccessControlService#checkAdminOrAbove}・"TEAM" scope）。
 * 無権原は {@link AccessControlService} が {@code BusinessException} を投げ
 * {@code GlobalExceptionHandler} が 403 変換する。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §P8</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/payment-items/{itemId}")
@Tag(name = "支払い明細 CSV エクスポート", description = "F08.9 P8 支払い明細ダウンロード（チーム ADMIN 専用）")
@RequiredArgsConstructor
public class TeamPaymentExportController {

    private final PaymentCsvExportService paymentCsvExportService;
    private final AccessControlService accessControlService;

    /**
     * 支払い明細 CSV をダウンロードする（チーム ADMIN 認可）。
     *
     * <p>認可: チーム ADMIN（{@link AccessControlService#checkAdminOrAbove}・"TEAM" scope）。
     * 無権原の場合は 403 を返す。</p>
     *
     * @param teamId チーム ID（パスパラメータ）
     * @param itemId 支払い項目 ID（パスパラメータ）
     * @return 200 OK + CSV バイト列（Content-Type: text/csv; charset=utf-8）
     */
    @GetMapping("/payments/export")
    @Operation(summary = "支払い明細 CSV エクスポート（F08.9 P8）")
    public ResponseEntity<byte[]> exportPaymentsCsv(
            @PathVariable Long teamId,
            @PathVariable Long itemId) {

        Long actorUserId = SecurityUtils.getCurrentUserId();
        // チーム ADMIN 認可（AccessControlService 正準・設計書 03 §3 マトリクス）。
        accessControlService.checkAdminOrAbove(actorUserId, teamId, "TEAM");

        String csvContent = paymentCsvExportService.exportToCsv(itemId, teamId, actorUserId);

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv;charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"payments.csv\"")
                .body(csvContent.getBytes(StandardCharsets.UTF_8));
    }
}
