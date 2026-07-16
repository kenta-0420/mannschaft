package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.service.FormCsvExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * フォーム提出 CSV エクスポートコントローラ（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code GET /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/submissions/export}。
 * テンプレート単位の全提出を EAV ピボット展開して RFC 4180 CSV を返す。
 * ADMIN 認可は {@link FormCsvExportService} 内で {@code AccessControlService.checkAdminOrAbove}
 * により担保する（認可根治戦役 Wave3-B4）。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/submissions")
@Tag(name = "フォーム CSV エクスポート", description = "F05.7 提出一覧 CSV 出力")
@RequiredArgsConstructor
public class FormCsvExportController {

    private final FormCsvExportService csvExportService;

    /**
     * 提出一覧を CSV としてダウンロードする。
     */
    @GetMapping("/export")
    @Operation(summary = "提出一覧 CSV エクスポート", description = "EAV ピボット展開した CSV を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "CSV 出力成功")
    public ResponseEntity<byte[]> exportCsv(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long templateId) {
        String csv = csvExportService.exportSubmissionsCsv(
                scopeType, scopeId, templateId, SecurityUtils.getCurrentUserId());
        // UTF-8 BOM を付与し、Excel 等の文字化けを防ぐ
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(body, 0, withBom, bom.length, body.length);

        String filename = String.format("form_submissions_template_%d.csv", templateId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", filename);
        return ResponseEntity.ok().headers(headers).body(withBom);
    }
}
