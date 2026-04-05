package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム回覧文書コントローラー。チームスコープの回覧文書CRUD・ステータス管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/circulations")
@Tag(name = "回覧板（チーム）", description = "F05.2 チームスコープの回覧文書管理")
@RequiredArgsConstructor
public class TeamCirculationDocumentController {

    private static final String SCOPE_TYPE = "TEAM";

    private final CirculationService circulationService;


    /**
     * 回覧文書一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チーム回覧文書一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DocumentResponse>> listDocuments(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DocumentResponse> result = circulationService.listDocuments(
                SCOPE_TYPE, teamId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 回覧文書詳細を取得する。
     */
    @GetMapping("/{documentId}")
    @Operation(summary = "チーム回覧文書詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocument(
            @PathVariable Long teamId,
            @PathVariable Long documentId) {
        DocumentResponse response = circulationService.getDocument(SCOPE_TYPE, teamId, documentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書を作成する。
     */
    @PostMapping
    @Operation(summary = "チーム回覧文書作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<DocumentResponse>> createDocument(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateDocumentRequest request) {
        DocumentResponse response = circulationService.createDocument(
                SCOPE_TYPE, teamId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 回覧文書を更新する。
     */
    @PatchMapping("/{documentId}")
    @Operation(summary = "チーム回覧文書更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<DocumentResponse>> updateDocument(
            @PathVariable Long teamId,
            @PathVariable Long documentId,
            @Valid @RequestBody UpdateDocumentRequest request) {
        DocumentResponse response = circulationService.updateDocument(SCOPE_TYPE, teamId, documentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書を公開する。
     */
    @PostMapping("/{documentId}/activate")
    @Operation(summary = "チーム回覧文書公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<DocumentResponse>> activateDocument(
            @PathVariable Long teamId,
            @PathVariable Long documentId) {
        DocumentResponse response = circulationService.activateDocument(SCOPE_TYPE, teamId, documentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書をキャンセルする。
     */
    @PostMapping("/{documentId}/cancel")
    @Operation(summary = "チーム回覧文書キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<DocumentResponse>> cancelDocument(
            @PathVariable Long teamId,
            @PathVariable Long documentId) {
        DocumentResponse response = circulationService.cancelDocument(SCOPE_TYPE, teamId, documentId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 回覧文書を削除する。
     */
    @DeleteMapping("/{documentId}")
    @Operation(summary = "チーム回覧文書削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long teamId,
            @PathVariable Long documentId) {
        circulationService.deleteDocument(SCOPE_TYPE, teamId, documentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 回覧文書統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "チーム回覧統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<DocumentStatsResponse>> getStats(
            @PathVariable Long teamId) {
        DocumentStatsResponse response = circulationService.getStats(SCOPE_TYPE, teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
