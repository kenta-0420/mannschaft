package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CreateAttachmentRequest;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 回覧添付ファイルコントローラー。回覧文書の添付ファイル管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/circulations/{documentId}/attachments")
@Tag(name = "回覧添付ファイル", description = "F05.2 回覧文書の添付ファイル管理")
@RequiredArgsConstructor
public class CirculationAttachmentController {

    private static final String SCOPE_TYPE = "TEAM";

    private final CirculationService circulationService;

    /**
     * 添付ファイル一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "添付ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AttachmentResponse>>> listAttachments(
            @PathVariable Long documentId) {
        List<AttachmentResponse> attachments = circulationService.listAttachments(documentId);
        return ResponseEntity.ok(ApiResponse.of(attachments));
    }

    /**
     * 添付ファイルを追加する。
     */
    @PostMapping
    @Operation(summary = "添付ファイル追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<AttachmentResponse>> addAttachment(
            @PathVariable Long documentId,
            @Valid @RequestBody CreateAttachmentRequest request) {
        // ドキュメントからscopeType/scopeIdを解決（現時点ではデフォルト値を使用）
        AttachmentResponse response = circulationService.addAttachment(
                SCOPE_TYPE, 0L, documentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
