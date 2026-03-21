package com.mannschaft.app.service.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.service.dto.BulkCreateResponse;
import com.mannschaft.app.service.dto.BulkCreateServiceRecordRequest;
import com.mannschaft.app.service.dto.ConfirmResponse;
import com.mannschaft.app.service.dto.CreateServiceRecordRequest;
import com.mannschaft.app.service.dto.DuplicateServiceRecordRequest;
import com.mannschaft.app.service.dto.ExportResponse;
import com.mannschaft.app.service.dto.ReactionRequest;
import com.mannschaft.app.service.dto.ReactionResponse;
import com.mannschaft.app.service.dto.RegisterAttachmentRequest;
import com.mannschaft.app.service.dto.AttachmentResponse;
import com.mannschaft.app.service.dto.ServiceHistorySummaryResponse;
import com.mannschaft.app.service.dto.ServiceRecordResponse;
import com.mannschaft.app.service.dto.UpdateServiceRecordRequest;
import com.mannschaft.app.service.dto.UploadUrlRequest;
import com.mannschaft.app.service.dto.UploadUrlResponse;
import com.mannschaft.app.service.service.ServiceRecordExportService;
import com.mannschaft.app.service.service.ServiceRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * サービス記録コントローラー。記録のCRUD・一括作成・複製・確定・添付・リアクション・エクスポートAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "サービス履歴", description = "F07.1 サービス履歴CRUD・検索・添付・リアクション・エクスポート")
@RequiredArgsConstructor
public class ServiceRecordController {

    private final ServiceRecordService recordService;
    private final ServiceRecordExportService exportService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    // ==================== 1. サービス記録一覧 ====================

    /**
     * チーム内のサービス履歴一覧を取得する。
     */
    @GetMapping("/teams/{teamId}/service-records")
    @Operation(summary = "サービス記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ServiceRecordResponse>> listRecords(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long memberUserId,
            @RequestParam(required = false) Long staffUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateTo,
            @RequestParam(required = false) String titleLike,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "serviceDate,desc") String sort,
            HttpServletRequest httpRequest) {
        String[] sortParts = sort.split(",");
        Sort sortObj = sortParts.length > 1
                ? Sort.by(Sort.Direction.fromString(sortParts[1]), sortParts[0])
                : Sort.by(Sort.Direction.DESC, sortParts[0]);

        // custom_field.{fieldId}={value} パラメータをパース
        Map<Long, String> customFieldFilters = new HashMap<>();
        httpRequest.getParameterMap().forEach((key, values) -> {
            if (key.startsWith("custom_field.") && values.length > 0) {
                try {
                    Long fieldId = Long.parseLong(key.substring("custom_field.".length()));
                    customFieldFilters.put(fieldId, values[0]);
                } catch (NumberFormatException ignored) {
                    // 不正なfieldIdは無視
                }
            }
        });

        Page<ServiceRecordResponse> result = recordService.listRecords(
                teamId, memberUserId, staffUserId, serviceDateFrom, serviceDateTo,
                titleLike, status, customFieldFilters, PageRequest.of(page, size, sortObj));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    // ==================== 2. サービス記録作成 ====================

    /**
     * サービス記録を作成する。
     */
    @PostMapping("/teams/{teamId}/service-records")
    @Operation(summary = "サービス記録作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ServiceRecordResponse>> createRecord(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateServiceRecordRequest request) {
        ServiceRecordResponse response = recordService.createRecord(teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 3. サービス記録一括作成 ====================

    /**
     * サービス記録を一括作成する。
     */
    @PostMapping("/teams/{teamId}/service-records/bulk")
    @Operation(summary = "サービス記録一括作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<BulkCreateResponse>> bulkCreate(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkCreateServiceRecordRequest request) {
        BulkCreateResponse response = recordService.bulkCreate(teamId, getCurrentUserId(), request);
        HttpStatus httpStatus = response.getFailedCount() != null && response.getFailedCount() > 0
                ? HttpStatus.MULTI_STATUS : HttpStatus.CREATED;
        return ResponseEntity.status(httpStatus).body(ApiResponse.of(response));
    }

    // ==================== 4. サービス記録詳細 ====================

    /**
     * サービス記録詳細を取得する。
     */
    @GetMapping("/teams/{teamId}/service-records/{id}")
    @Operation(summary = "サービス記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ServiceRecordResponse>> getRecord(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ServiceRecordResponse response = recordService.getRecord(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 5. サービス記録更新 ====================

    /**
     * サービス記録を更新する。
     */
    @PutMapping("/teams/{teamId}/service-records/{id}")
    @Operation(summary = "サービス記録更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ServiceRecordResponse>> updateRecord(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateServiceRecordRequest request) {
        ServiceRecordResponse response = recordService.updateRecord(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 6. 下書き確定 ====================

    /**
     * 下書き記録を確定する。
     */
    @PatchMapping("/teams/{teamId}/service-records/{id}/confirm")
    @Operation(summary = "下書き確定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確定成功")
    public ResponseEntity<ApiResponse<ConfirmResponse>> confirmRecord(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ConfirmResponse response = recordService.confirmRecord(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 7. サービス記録削除 ====================

    /**
     * サービス記録を論理削除する。
     */
    @DeleteMapping("/teams/{teamId}/service-records/{id}")
    @Operation(summary = "サービス記録削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteRecord(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        recordService.deleteRecord(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ==================== 8. 記録複製 ====================

    /**
     * 既存記録を複製する。
     */
    @PostMapping("/teams/{teamId}/service-records/{id}/duplicate")
    @Operation(summary = "記録複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ServiceRecordResponse>> duplicateRecord(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @RequestBody(required = false) DuplicateServiceRecordRequest request) {
        ServiceRecordResponse response = recordService.duplicateRecord(teamId, id, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 9. メンバー履歴一覧 ====================

    /**
     * 特定メンバーの履歴一覧を取得する。
     */
    @GetMapping("/teams/{teamId}/members/{userId}/service-history")
    @Operation(summary = "メンバー履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ServiceRecordResponse>> getMemberHistory(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ServiceRecordResponse> result = recordService.getMemberHistory(teamId, userId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    // ==================== 10. メンバー履歴サマリー ====================

    /**
     * 特定メンバーの履歴サマリー統計を取得する。
     */
    @GetMapping("/teams/{teamId}/members/{userId}/service-history/summary")
    @Operation(summary = "メンバー履歴サマリー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ServiceHistorySummaryResponse>> getMemberSummary(
            @PathVariable Long teamId,
            @PathVariable Long userId,
            @RequestParam(defaultValue = "6") int months) {
        ServiceHistorySummaryResponse response = recordService.getMemberSummary(teamId, userId, months);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 11. 自分の履歴 ====================

    /**
     * 自分のサービス履歴を全チーム横断で取得する。
     */
    @GetMapping("/service-records/me")
    @Operation(summary = "自分のサービス履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ServiceRecordResponse>> getMyRecords(
            @RequestParam(required = false) Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ServiceRecordResponse> result = recordService.getMyRecords(
                getCurrentUserId(), teamId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    // ==================== 19. リアクション追加 ====================

    /**
     * リアクションを追加する。
     */
    @PostMapping("/teams/{teamId}/service-records/{id}/reactions")
    @Operation(summary = "リアクション追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<ReactionResponse>> addReaction(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ReactionRequest request) {
        ReactionResponse response = recordService.addReaction(teamId, id, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 20. リアクション削除 ====================

    /**
     * リアクションを削除する。
     */
    @DeleteMapping("/teams/{teamId}/service-records/{id}/reactions")
    @Operation(summary = "リアクション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteReaction(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        recordService.deleteReaction(teamId, id, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ==================== 21. アップロードURL発行 ====================

    /**
     * アップロード用 Pre-signed URL を発行する。
     */
    @PostMapping("/teams/{teamId}/service-records/{id}/attachments/upload-url")
    @Operation(summary = "アップロードURL発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<UploadUrlResponse>> generateUploadUrl(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UploadUrlRequest request) {
        UploadUrlResponse response = recordService.generateUploadUrl(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 22. 添付ファイル登録 ====================

    /**
     * 添付ファイルメタデータを登録する。
     */
    @PostMapping("/teams/{teamId}/service-records/{id}/attachments")
    @Operation(summary = "添付ファイル登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<AttachmentResponse>> registerAttachment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody RegisterAttachmentRequest request) {
        AttachmentResponse response = recordService.registerAttachment(teamId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 23. 添付ファイル削除 ====================

    /**
     * 添付ファイルを削除する。
     */
    @DeleteMapping("/teams/{teamId}/service-records/{id}/attachments/{attachmentId}")
    @Operation(summary = "添付ファイル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAttachment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @PathVariable Long attachmentId) {
        recordService.deleteAttachment(teamId, id, attachmentId);
        return ResponseEntity.noContent().build();
    }

    // ==================== 33. CSVエクスポート ====================

    /**
     * CSV エクスポートする。
     */
    @GetMapping("/teams/{teamId}/service-records/export")
    @Operation(summary = "CSVエクスポート")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "エクスポート成功")
    public ResponseEntity<?> exportCsv(
            @PathVariable Long teamId,
            @RequestParam(required = false) Long memberUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceDateTo,
            HttpServletResponse httpResponse) {
        ExportResponse asyncResult = exportService.exportOrNull(teamId, memberUserId, serviceDateFrom, serviceDateTo);

        if (asyncResult != null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(asyncResult));
        }

        // ストリーミングレスポンス
        httpResponse.setContentType("text/csv; charset=UTF-8");
        httpResponse.setHeader("Content-Disposition", "attachment; filename=service_records.csv");
        try {
            exportService.writeCsv(teamId, memberUserId, serviceDateFrom, serviceDateTo,
                    httpResponse.getOutputStream());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }
}
