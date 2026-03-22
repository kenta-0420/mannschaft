package com.mannschaft.app.resident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.resident.dto.ResidentDocumentResponse;
import com.mannschaft.app.resident.dto.UploadDocumentRequest;
import com.mannschaft.app.resident.service.ResidentDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム居住者書類コントローラー。
 */
@RestController
@Tag(name = "居住者書類（チーム）", description = "F09.1 チーム居住者書類管理")
@RequiredArgsConstructor
public class TeamResidentDocumentController {

    private final ResidentDocumentService documentService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @PostMapping("/api/v1/teams/{teamId}/residents/{residentId}/documents")
    @Operation(summary = "書類アップロード")
    public ResponseEntity<ApiResponse<ResidentDocumentResponse>> upload(
            @PathVariable Long teamId, @PathVariable Long residentId,
            @Valid @RequestBody UploadDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.of(documentService.upload(residentId, getCurrentUserId(), request)));
    }

    @GetMapping("/api/v1/teams/{teamId}/residents/{residentId}/documents")
    @Operation(summary = "書類一覧")
    public ResponseEntity<ApiResponse<List<ResidentDocumentResponse>>> list(
            @PathVariable Long teamId, @PathVariable Long residentId) {
        return ResponseEntity.ok(ApiResponse.of(documentService.listByResident(residentId)));
    }

    @DeleteMapping("/api/v1/teams/{teamId}/residents/{residentId}/documents/{docId}")
    @Operation(summary = "書類削除")
    public ResponseEntity<Void> delete(
            @PathVariable Long teamId, @PathVariable Long residentId, @PathVariable Long docId) {
        documentService.delete(residentId, docId);
        return ResponseEntity.noContent().build();
    }
}
