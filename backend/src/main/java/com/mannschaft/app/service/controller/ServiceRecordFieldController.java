package com.mannschaft.app.service.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.service.dto.CreateFieldRequest;
import com.mannschaft.app.service.dto.FieldResponse;
import com.mannschaft.app.service.dto.FieldSortOrderRequest;
import com.mannschaft.app.service.dto.SettingsResponse;
import com.mannschaft.app.service.dto.SortOrderResponse;
import com.mannschaft.app.service.dto.UpdateFieldRequest;
import com.mannschaft.app.service.dto.UpdateSettingsRequest;
import com.mannschaft.app.service.service.ServiceRecordFieldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * カスタムフィールド定義・設定コントローラー。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "サービス履歴フィールド・設定", description = "F07.1 カスタムフィールド定義・設定管理")
@RequiredArgsConstructor
public class ServiceRecordFieldController {

    private final ServiceRecordFieldService fieldService;

    // ==================== 12. フィールド一覧 ====================

    /**
     * カスタムフィールド定義一覧を取得する。
     */
    @GetMapping("/service-record-fields")
    @Operation(summary = "カスタムフィールド一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FieldResponse>>> listFields(@PathVariable Long teamId) {
        List<FieldResponse> response = fieldService.listFields(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 13. フィールド作成 ====================

    /**
     * カスタムフィールドを作成する。
     */
    @PostMapping("/service-record-fields")
    @Operation(summary = "カスタムフィールド作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FieldResponse>> createField(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFieldRequest request) {
        FieldResponse response = fieldService.createField(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ==================== 14. フィールド更新 ====================

    /**
     * カスタムフィールドを更新する（再有効化含む）。
     */
    @PutMapping("/service-record-fields/{id}")
    @Operation(summary = "カスタムフィールド更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FieldResponse>> updateField(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateFieldRequest request) {
        FieldResponse response = fieldService.updateField(teamId, id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 15. フィールド無効化 ====================

    /**
     * カスタムフィールドを無効化する。
     */
    @DeleteMapping("/service-record-fields/{id}")
    @Operation(summary = "カスタムフィールド無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateField(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        fieldService.deactivateField(teamId, id);
        return ResponseEntity.noContent().build();
    }

    // ==================== 16. フィールド並び替え ====================

    /**
     * カスタムフィールドの並び順を一括更新する。
     */
    @PatchMapping("/service-record-fields/sort-order")
    @Operation(summary = "カスタムフィールド並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SortOrderResponse>> updateSortOrder(
            @PathVariable Long teamId,
            @Valid @RequestBody FieldSortOrderRequest request) {
        SortOrderResponse response = fieldService.updateSortOrder(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 17. 設定取得 ====================

    /**
     * 機能設定を取得する。
     */
    @GetMapping("/service-records/settings")
    @Operation(summary = "機能設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SettingsResponse>> getSettings(@PathVariable Long teamId) {
        SettingsResponse response = fieldService.getSettings(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    // ==================== 18. 設定更新 ====================

    /**
     * 機能設定を更新する。
     */
    @PutMapping("/service-records/settings")
    @Operation(summary = "機能設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SettingsResponse>> updateSettings(
            @PathVariable Long teamId,
            @Valid @RequestBody UpdateSettingsRequest request) {
        SettingsResponse response = fieldService.updateSettings(teamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
