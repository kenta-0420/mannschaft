package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CirculationAttachmentPresignRequest;
import com.mannschaft.app.circulation.dto.CirculationAttachmentPresignResponse;
import com.mannschaft.app.circulation.dto.CreateAttachmentRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
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
     *
     * <p><b>F13 Phase 5-a</b>: {@code SCOPE_TYPE = "TEAM"} 直書きバグを修正。
     * ドキュメントエンティティから動的に scopeType/scopeId を取得する。</p>
     */
    @PostMapping
    @Operation(summary = "添付ファイル追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<AttachmentResponse>> addAttachment(
            @PathVariable Long documentId,
            @Valid @RequestBody CreateAttachmentRequest request) {
        // F13 Phase 5-a: ドキュメントから動的にscopeType/scopeIdを解決（SCOPE_TYPE直書きバグ修正）
        CirculationDocumentEntity doc = circulationService.findDocumentById(documentId);
        AttachmentResponse response = circulationService.addAttachment(
                doc.getScopeType(), doc.getScopeId(), documentId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * F13 Phase 5-a: 添付ファイルアップロード用の Presigned URL を発行する。
     *
     * <p>クライアントはこのエンドポイントで {@code uploadUrl} と {@code fileKey} を取得し、
     * {@code uploadUrl} を使って R2 に直接 PUT する。完了後、{@code fileKey} を
     * {@code POST /api/v1/circulations/{documentId}/attachments} に渡してメタデータを登録する。</p>
     */
    @PostMapping("/upload-url")
    @Operation(summary = "添付ファイルアップロード用 presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<CirculationAttachmentPresignResponse>> presignUpload(
            @PathVariable Long documentId,
            @Valid @RequestBody CirculationAttachmentPresignRequest request) {
        CirculationAttachmentPresignResponse response =
                circulationService.presignAttachmentUpload(documentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
