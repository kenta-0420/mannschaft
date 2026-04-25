package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.service.ShiftPdfService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * シフト PDF エクスポートコントローラー。
 * F03.5 §PDF出力 — チーム全体表・個人タイムラインの PDF をダウンロードする。
 * SUPPORTER ロールは 403 を返す。
 */
@RestController
@RequestMapping("/api/v1/shifts/schedules/{scheduleId}/pdf")
@Tag(name = "シフト PDF エクスポート", description = "F03.5 シフト PDF ダウンロード")
@RequiredArgsConstructor
public class ShiftPdfController {

    private final ShiftPdfService shiftPdfService;

    /**
     * シフト PDF をダウンロードする。
     * SUPPORTER ロールはアクセス不可。
     *
     * @param scheduleId スケジュール ID
     * @param layout     レイアウト種別（"team" または "personal"）
     * @return PDF バイト列
     */
    @GetMapping(produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "シフト PDF ダウンロード")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "PDF ダウンロード成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "SUPPORTER ロールはアクセス不可")
    @PreAuthorize("!hasRole('SUPPORTER')")
    public ResponseEntity<byte[]> exportPdf(
            @PathVariable Long scheduleId,
            @RequestParam(defaultValue = "team") String layout) {

        Long requesterId = SecurityUtils.getCurrentUserId();

        byte[] pdfBytes = "personal".equals(layout)
                ? shiftPdfService.generatePersonalPdf(scheduleId, requesterId)
                : shiftPdfService.generateTeamPdf(scheduleId, requesterId);

        String filename = "personal".equals(layout)
                ? "shift-personal-" + scheduleId + ".pdf"
                : "shift-team-" + scheduleId + ".pdf";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(pdfBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);
    }
}
