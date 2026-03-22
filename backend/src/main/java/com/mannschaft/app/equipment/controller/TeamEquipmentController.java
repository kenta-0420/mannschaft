package com.mannschaft.app.equipment.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.equipment.dto.AssignEquipmentRequest;
import com.mannschaft.app.equipment.dto.AssignmentResponse;
import com.mannschaft.app.equipment.dto.BulkAssignRequest;
import com.mannschaft.app.equipment.dto.BulkAssignResponse;
import com.mannschaft.app.equipment.dto.BulkReturnRequest;
import com.mannschaft.app.equipment.dto.BulkReturnResponse;
import com.mannschaft.app.equipment.dto.ConsumeEquipmentRequest;
import com.mannschaft.app.equipment.dto.ConsumeResponse;
import com.mannschaft.app.equipment.dto.CreateEquipmentItemRequest;
import com.mannschaft.app.equipment.dto.EquipmentItemResponse;
import com.mannschaft.app.equipment.dto.PresignedUrlRequest;
import com.mannschaft.app.equipment.dto.PresignedUrlResponse;
import com.mannschaft.app.equipment.dto.QrCodeResponse;
import com.mannschaft.app.equipment.dto.ReturnEquipmentRequest;
import com.mannschaft.app.equipment.dto.ReturnResponse;
import com.mannschaft.app.equipment.dto.UpdateEquipmentItemRequest;
import com.mannschaft.app.equipment.service.EquipmentAssignmentService;
import com.mannschaft.app.equipment.service.EquipmentItemService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム備品コントローラー。チームスコープの備品CRUD・貸出・返却・消費・画像管理・QRコードAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/equipment")
@Tag(name = "チーム備品管理", description = "F07.3 チーム備品CRUD・貸出・返却・消費・画像・QR")
@RequiredArgsConstructor
public class TeamEquipmentController {

    private final EquipmentItemService itemService;
    private final EquipmentAssignmentService assignmentService;


    // ===================== 備品CRUD =====================

    /**
     * チーム備品一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チーム備品一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<EquipmentItemResponse>> listEquipment(
            @PathVariable Long teamId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameLike,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        Sort sortObj = parseSortParam(sort);
        Page<EquipmentItemResponse> result = itemService.listByTeam(teamId, category, status, nameLike,
                PageRequest.of(page, Math.min(size, 100), sortObj));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チーム備品詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "チーム備品詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EquipmentItemResponse>> getEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        EquipmentItemResponse response = itemService.getByTeam(teamId, id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム備品を作成する。
     */
    @PostMapping
    @Operation(summary = "チーム備品作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<EquipmentItemResponse>> createEquipment(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateEquipmentItemRequest request) {
        EquipmentItemResponse response = itemService.createForTeam(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チーム備品を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "チーム備品更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EquipmentItemResponse>> updateEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateEquipmentItemRequest request) {
        EquipmentItemResponse response = itemService.updateForTeam(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム備品を削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "チーム備品削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        itemService.deleteForTeam(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ===================== 貸出・返却 =====================

    /**
     * チーム備品を貸出する。
     */
    @PostMapping("/{id}/assign")
    @Operation(summary = "チーム備品貸出")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "貸出成功")
    public ResponseEntity<ApiResponse<AssignmentResponse>> assignEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody AssignEquipmentRequest request) {
        AssignmentResponse response = assignmentService.assignForTeam(teamId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チーム備品を返却する。
     */
    @PatchMapping("/{id}/return")
    @Operation(summary = "チーム備品返却")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "返却成功")
    public ResponseEntity<ApiResponse<ReturnResponse>> returnEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ReturnEquipmentRequest request) {
        ReturnResponse response = assignmentService.returnForTeam(teamId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ===================== 履歴・遅延 =====================

    /**
     * チーム備品の貸出・返却履歴を取得する。
     */
    @GetMapping("/{id}/history")
    @Operation(summary = "チーム備品履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getHistory(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AssignmentResponse> result = assignmentService.getHistoryForTeam(teamId, id, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チーム備品の返却遅延一覧を取得する。
     */
    @GetMapping("/overdue")
    @Operation(summary = "チーム備品遅延一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<AssignmentResponse>> getOverdue(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AssignmentResponse> result = assignmentService.getOverdueForTeam(teamId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    // ===================== カテゴリ =====================

    /**
     * チーム備品カテゴリ一覧を取得する。
     */
    @GetMapping("/categories")
    @Operation(summary = "チーム備品カテゴリ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<String>>> getCategories(
            @PathVariable Long teamId) {
        List<String> categories = itemService.getCategoriesByTeam(teamId);
        return ResponseEntity.ok(ApiResponse.of(categories));
    }

    // ===================== 消耗品消費 =====================

    /**
     * チーム備品の消耗品を消費する。
     */
    @PostMapping("/{id}/consume")
    @Operation(summary = "チーム消耗品消費")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "消費成功")
    public ResponseEntity<ApiResponse<ConsumeResponse>> consumeEquipment(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody ConsumeEquipmentRequest request) {
        ConsumeResponse response = assignmentService.consumeForTeam(teamId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ===================== 画像管理 =====================

    /**
     * チーム備品画像アップロード用 Pre-signed URL を取得する。
     */
    @PostMapping("/{id}/image/presigned-url")
    @Operation(summary = "チーム備品画像Pre-signed URL取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<PresignedUrlResponse>> getPresignedUrl(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody PresignedUrlRequest request) {
        PresignedUrlResponse response = itemService.getPresignedUrlForTeam(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム備品画像を削除する。
     */
    @DeleteMapping("/{id}/image")
    @Operation(summary = "チーム備品画像削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        itemService.deleteImageForTeam(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ===================== 一括操作 =====================

    /**
     * チーム備品を一括貸出する。
     */
    @PostMapping("/{id}/assign-bulk")
    @Operation(summary = "チーム備品一括貸出")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "一括貸出成功")
    public ResponseEntity<ApiResponse<BulkAssignResponse>> bulkAssign(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody BulkAssignRequest request) {
        BulkAssignResponse response = assignmentService.bulkAssignForTeam(teamId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チーム備品を一括返却する。
     */
    @PatchMapping("/{id}/return-bulk")
    @Operation(summary = "チーム備品一括返却")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "一括返却成功")
    public ResponseEntity<ApiResponse<BulkReturnResponse>> bulkReturn(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody BulkReturnRequest request) {
        BulkReturnResponse response = assignmentService.bulkReturnForTeam(teamId, id, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ===================== QRコード =====================

    /**
     * チーム備品のQRコード一覧を取得する。
     */
    @GetMapping("/qr-codes")
    @Operation(summary = "チーム備品QRコード一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<QrCodeResponse>>> getQrCodes(
            @PathVariable Long teamId,
            @RequestParam(required = false) String ids,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String nameLike) {
        List<QrCodeResponse> response = itemService.getQrCodesByTeam(teamId, ids, category, status, nameLike);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ===================== ヘルパー =====================

    private Sort parseSortParam(String sort) {
        String[] parts = sort.split(",");
        String property = parts[0];
        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(direction, property);
    }
}
