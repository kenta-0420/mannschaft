package com.mannschaft.app.proxyvote.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.pdf.PdfFileNameBuilder;
import com.mannschaft.app.common.pdf.PdfResponseHelper;
import com.mannschaft.app.proxyvote.dto.AttachmentResponse;
import com.mannschaft.app.proxyvote.service.ProxyVoteAttachmentService;
import com.mannschaft.app.proxyvote.service.ProxyVoteExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * エクスポート・添付ファイルコントローラー。CSV/PDF エクスポートとセッション添付ファイルAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/proxy-votes")
@Tag(name = "議決権行使・委任状", description = "F08.3 エクスポート・添付ファイル")
@RequiredArgsConstructor
public class ProxyVoteExportController {

    private final ProxyVoteExportService exportService;
    private final ProxyVoteAttachmentService attachmentService;

    // JwtAuthenticationFilter実装後にSecurityContextHolderから取得に変更予定
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 投票結果を CSV でダウンロードする。
     */
    @GetMapping("/{id}/results/csv")
    @Operation(summary = "投票結果 CSV エクスポート")
    public ResponseEntity<byte[]> exportResultsCsv(@PathVariable Long id) {
        byte[] csv = exportService.exportResultsCsv(id);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"vote_results_" + id + "_" + timestamp + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * 議事録 PDF をダウンロードする。
     */
    @GetMapping("/{id}/minutes-pdf")
    @Operation(summary = "議事録 PDF エクスポート")
    public ResponseEntity<byte[]> exportMinutesPdf(@PathVariable Long id) {
        byte[] pdf = exportService.exportMinutesPdf(id);

        String fileName = PdfFileNameBuilder.of("議事録")
                .date(LocalDate.now())
                .identifier(String.valueOf(id))
                .build();

        return PdfResponseHelper.toResponse(pdf, fileName);
    }

    /**
     * セッションに添付ファイルを追加する。
     */
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "セッション添付ファイル追加")
    public ResponseEntity<ApiResponse<AttachmentResponse>> addSessionAttachment(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "attachment_type", required = false) String attachmentType) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(attachmentService.addSessionAttachment(id, file, attachmentType, getCurrentUserId())));
    }

    /**
     * 添付ファイルを削除する。
     */
    @DeleteMapping("/attachments/{attachmentId}")
    @Operation(summary = "添付ファイル削除")
    public ResponseEntity<Void> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.deleteAttachment(attachmentId);
        return ResponseEntity.noContent().build();
    }
}
