package com.mannschaft.app.resident.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.resident.dto.BatchCreateDwellingUnitRequest;
import com.mannschaft.app.resident.dto.CreateDwellingUnitRequest;
import com.mannschaft.app.resident.dto.DwellingUnitResponse;
import com.mannschaft.app.resident.service.DwellingUnitService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 組織居室管理コントローラー。
 */
@RestController
@Tag(name = "居室管理（組織）", description = "F09.1 組織居室CRUD")
@RequiredArgsConstructor
public class OrgDwellingUnitController {

    private final DwellingUnitService dwellingUnitService;

    @GetMapping("/api/v1/organizations/{orgId}/dwelling-units")
    @Operation(summary = "居室一覧")
    public ResponseEntity<PagedResponse<DwellingUnitResponse>> list(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DwellingUnitResponse> result = dwellingUnitService.listByOrganization(orgId, PageRequest.of(page, Math.min(size, 100)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping("/api/v1/organizations/{orgId}/dwelling-units")
    @Operation(summary = "居室作成")
    public ResponseEntity<ApiResponse<DwellingUnitResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateDwellingUnitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dwellingUnitService.createForOrganization(orgId, request)));
    }

    @GetMapping("/api/v1/organizations/{orgId}/dwelling-units/{id}")
    @Operation(summary = "居室詳細")
    public ResponseEntity<ApiResponse<DwellingUnitResponse>> get(
            @PathVariable Long orgId, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(dwellingUnitService.getByOrganization(orgId, id)));
    }

    @PutMapping("/api/v1/organizations/{orgId}/dwelling-units/{id}")
    @Operation(summary = "居室更新")
    public ResponseEntity<ApiResponse<DwellingUnitResponse>> update(
            @PathVariable Long orgId, @PathVariable Long id,
            @Valid @RequestBody CreateDwellingUnitRequest request) {
        return ResponseEntity.ok(ApiResponse.of(dwellingUnitService.updateForOrganization(orgId, id, request)));
    }

    @DeleteMapping("/api/v1/organizations/{orgId}/dwelling-units/{id}")
    @Operation(summary = "居室削除")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable Long id) {
        dwellingUnitService.deleteForOrganization(orgId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/organizations/{orgId}/dwelling-units/batch")
    @Operation(summary = "居室一括登録")
    public ResponseEntity<ApiResponse<List<DwellingUnitResponse>>> batchCreate(
            @PathVariable Long orgId,
            @Valid @RequestBody BatchCreateDwellingUnitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dwellingUnitService.batchCreateForOrganization(orgId, request)));
    }
}
