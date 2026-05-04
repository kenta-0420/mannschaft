package com.mannschaft.app.timetable.notes.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.timetable.notes.dto.AttachmentConfirmRequest;
import com.mannschaft.app.timetable.notes.dto.AttachmentDownloadUrlResponse;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignRequest;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignResponse;
import com.mannschaft.app.timetable.notes.dto.AttachmentResponse;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteAttachmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F03.15 Phase 3 メモ添付ファイル コントローラ。
 */
@RestController
@RequestMapping("/api/v1/me/timetable-slot-notes")
@Tag(name = "個人メモ-添付", description = "F03.15 メモ添付ファイル（R2 ストレージ）")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TimetableSlotUserNoteAttachmentController {

    private final TimetableSlotUserNoteAttachmentService service;

    @GetMapping("/{noteId}/attachments")
    @Operation(summary = "メモ添付一覧")
    public ResponseEntity<ApiResponse<List<AttachmentResponse>>> list(@PathVariable Long noteId) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<AttachmentResponse> data = service.listForNote(noteId, userId).stream()
                .map(AttachmentResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PostMapping("/{noteId}/attachments/presign")
    @Operation(summary = "アップロード用 Pre-signed URL 発行（5分 TTL）")
    public ResponseEntity<ApiResponse<AttachmentPresignResponse>> presign(
            @PathVariable Long noteId,
            @Valid @RequestBody AttachmentPresignRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AttachmentPresignResponse data = service.presign(noteId, userId, request);
        return ResponseEntity.ok(ApiResponse.of(data));
    }

    @PostMapping("/{noteId}/attachments/confirm")
    @Operation(summary = "アップロード完了通知（メタ確定 + magic byte 検証）")
    public ResponseEntity<ApiResponse<AttachmentResponse>> confirm(
            @PathVariable Long noteId,
            @Valid @RequestBody ConfirmFullRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        AttachmentConfirmRequest confirm = new AttachmentConfirmRequest(request.r2ObjectKey());
        AttachmentPresignRequest original = new AttachmentPresignRequest(
                request.fileName(), request.contentType(), request.sizeBytes());
        var entity = service.confirm(noteId, userId, confirm, original);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(AttachmentResponse.from(entity)));
    }

    @GetMapping("/attachments/{attachmentId}/download-url")
    @Operation(summary = "ダウンロード用 Pre-signed URL 発行（5分 TTL）")
    public ResponseEntity<ApiResponse<AttachmentDownloadUrlResponse>> downloadUrl(
            @PathVariable Long attachmentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String url = service.generateDownloadUrl(attachmentId, userId);
        return ResponseEntity.ok(ApiResponse.of(
                new AttachmentDownloadUrlResponse(url,
                        TimetableSlotUserNoteAttachmentService.PRESIGN_TTL.toSeconds())));
    }

    @DeleteMapping("/attachments/{attachmentId}")
    @Operation(summary = "添付削除（論理）")
    public ResponseEntity<Void> delete(@PathVariable Long attachmentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        service.delete(attachmentId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * confirm 用拡張リクエスト（presign 時の情報も併せて受け取る）。
     *
     * <p>クライアントは presign で得た {@code r2_object_key} に加えて、
     * presign 発行時と同じ {@code file_name / content_type / size_bytes} を再送する。
     * これによりサーバは presign のセッションを保持せず stateless で confirm 処理できる。</p>
     */
    public record ConfirmFullRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("r2_object_key")
            @jakarta.validation.constraints.NotBlank String r2ObjectKey,
            @com.fasterxml.jackson.annotation.JsonProperty("file_name")
            @jakarta.validation.constraints.NotBlank String fileName,
            @com.fasterxml.jackson.annotation.JsonProperty("content_type")
            @jakarta.validation.constraints.NotBlank String contentType,
            @com.fasterxml.jackson.annotation.JsonProperty("size_bytes")
            @jakarta.validation.constraints.Positive Long sizeBytes
    ) {
    }
}
