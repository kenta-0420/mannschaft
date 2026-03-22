package com.mannschaft.app.resident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.resident.dto.CreateResidentRequest;
import com.mannschaft.app.resident.dto.ResidentResponse;
import com.mannschaft.app.resident.dto.UpdateResidentRequest;
import com.mannschaft.app.resident.service.ResidentRegistryService;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 組織居住者管理コントローラー。
 */
@RestController
@Tag(name = "居住者管理（組織）", description = "F09.1 組織居住者CRUD")
@RequiredArgsConstructor
public class OrgResidentController {

    private final ResidentRegistryService residentService;

    private Long getCurrentUserId() {
        return 1L;
    }

    @GetMapping("/api/v1/organizations/{orgId}/dwelling-units/{unitId}/residents")
    @Operation(summary = "居住者一覧")
    public ResponseEntity<ApiResponse<List<ResidentResponse>>> listByUnit(
            @PathVariable Long orgId, @PathVariable Long unitId) {
        return ResponseEntity.ok(ApiResponse.of(residentService.listByUnit(unitId)));
    }

    @PostMapping("/api/v1/organizations/{orgId}/dwelling-units/{unitId}/residents")
    @Operation(summary = "居住者登録")
    public ResponseEntity<ApiResponse<ResidentResponse>> create(
            @PathVariable Long orgId, @PathVariable Long unitId,
            @Valid @RequestBody CreateResidentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(residentService.create(unitId, request)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/residents/{id}")
    @Operation(summary = "居住者更新")
    public ResponseEntity<ApiResponse<ResidentResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody UpdateResidentRequest request) {
        return ResponseEntity.ok(ApiResponse.of(residentService.update(id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/residents/{id}")
    @Operation(summary = "居住者削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        residentService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/api/v1/organizations/{orgId}/residents/{id}/verify")
    @Operation(summary = "居住者確認")
    public ResponseEntity<ApiResponse<ResidentResponse>> verify(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(residentService.verify(id, getCurrentUserId())));
    }

    @PatchMapping("/api/v1/organizations/{orgId}/residents/{id}/move-out")
    @Operation(summary = "退去処理")
    public ResponseEntity<ApiResponse<ResidentResponse>> moveOut(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(residentService.moveOut(id, LocalDate.now())));
    }
}
