package com.mannschaft.app.facility.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.facility.dto.AvailabilityResponse;
import com.mannschaft.app.facility.dto.BulkCreateFacilityRequest;
import com.mannschaft.app.facility.dto.CreateEquipmentRequest;
import com.mannschaft.app.facility.dto.CreateFacilityRequest;
import com.mannschaft.app.facility.dto.EquipmentResponse;
import com.mannschaft.app.facility.dto.FacilityDetailResponse;
import com.mannschaft.app.facility.dto.FacilityResponse;
import com.mannschaft.app.facility.dto.TimeRateResponse;
import com.mannschaft.app.facility.dto.UpdateEquipmentRequest;
import com.mannschaft.app.facility.dto.UpdateFacilityRequest;
import com.mannschaft.app.facility.dto.UpdateTimeRatesRequest;
import com.mannschaft.app.facility.dto.UpdateUsageRuleRequest;
import com.mannschaft.app.facility.dto.UsageRuleResponse;
import com.mannschaft.app.facility.service.FacilityEquipmentService;
import com.mannschaft.app.facility.service.FacilityRuleService;
import com.mannschaft.app.facility.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * チーム施設管理コントローラー。施設CRUD・ルール・料金・備品・空き状況APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/facilities")
@Tag(name = "チーム施設管理", description = "F09.5 チーム共用施設CRUD・ルール・料金・備品管理")
@RequiredArgsConstructor
public class TeamFacilityController {

    private static final String SCOPE_TYPE = "TEAM";

    private final FacilityService facilityService;
    private final FacilityRuleService ruleService;
    private final FacilityEquipmentService equipmentService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームの施設一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "施設一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FacilityResponse>> listFacilities(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FacilityResponse> result = facilityService.listFacilities(SCOPE_TYPE, teamId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 施設を作成する。
     */
    @PostMapping
    @Operation(summary = "施設作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FacilityResponse>> createFacility(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateFacilityRequest request) {
        FacilityResponse response = facilityService.createFacility(SCOPE_TYPE, teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 施設を一括作成する。
     */
    @PostMapping("/bulk-create")
    @Operation(summary = "施設一括作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<List<FacilityResponse>>> bulkCreateFacilities(
            @PathVariable Long teamId,
            @Valid @RequestBody BulkCreateFacilityRequest request) {
        List<FacilityResponse> responses = facilityService.bulkCreateFacilities(
                SCOPE_TYPE, teamId, getCurrentUserId(), request.getFacilities());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(responses));
    }

    /**
     * 施設詳細を取得する。
     */
    @GetMapping("/{facilityId}")
    @Operation(summary = "施設詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FacilityDetailResponse>> getFacility(
            @PathVariable Long teamId,
            @PathVariable Long facilityId) {
        FacilityDetailResponse response = facilityService.getFacility(SCOPE_TYPE, teamId, facilityId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 施設を更新する。
     */
    @PutMapping("/{facilityId}")
    @Operation(summary = "施設更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FacilityDetailResponse>> updateFacility(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @Valid @RequestBody UpdateFacilityRequest request) {
        FacilityDetailResponse response = facilityService.updateFacility(SCOPE_TYPE, teamId, facilityId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 施設を削除する。
     */
    @DeleteMapping("/{facilityId}")
    @Operation(summary = "施設削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteFacility(
            @PathVariable Long teamId,
            @PathVariable Long facilityId) {
        facilityService.deleteFacility(SCOPE_TYPE, teamId, facilityId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 利用ルールを取得する。
     */
    @GetMapping("/{facilityId}/rules")
    @Operation(summary = "利用ルール取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UsageRuleResponse>> getUsageRule(
            @PathVariable Long teamId,
            @PathVariable Long facilityId) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        UsageRuleResponse response = ruleService.getUsageRule(facilityId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 利用ルールを更新する。
     */
    @PutMapping("/{facilityId}/rules")
    @Operation(summary = "利用ルール更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<UsageRuleResponse>> updateUsageRule(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @Valid @RequestBody UpdateUsageRuleRequest request) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        UsageRuleResponse response = ruleService.updateUsageRule(facilityId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 時間帯別料金一覧を取得する。
     */
    @GetMapping("/{facilityId}/rates")
    @Operation(summary = "時間帯別料金一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TimeRateResponse>>> getTimeRates(
            @PathVariable Long teamId,
            @PathVariable Long facilityId) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        List<TimeRateResponse> responses = ruleService.getTimeRates(facilityId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 時間帯別料金を一括置換する。
     */
    @PutMapping("/{facilityId}/rates")
    @Operation(summary = "時間帯別料金一括置換")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<List<TimeRateResponse>>> replaceTimeRates(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @Valid @RequestBody UpdateTimeRatesRequest request) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        List<TimeRateResponse> responses = ruleService.replaceTimeRates(facilityId, request);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 備品一覧を取得する。
     */
    @GetMapping("/{facilityId}/equipment")
    @Operation(summary = "備品一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EquipmentResponse>>> listEquipment(
            @PathVariable Long teamId,
            @PathVariable Long facilityId) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        List<EquipmentResponse> responses = equipmentService.listEquipment(facilityId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 備品を作成する。
     */
    @PostMapping("/{facilityId}/equipment")
    @Operation(summary = "備品作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<EquipmentResponse>> createEquipment(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @Valid @RequestBody CreateEquipmentRequest request) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        EquipmentResponse response = equipmentService.createEquipment(facilityId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 備品を更新する。
     */
    @PutMapping("/{facilityId}/equipment/{equipmentId}")
    @Operation(summary = "備品更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EquipmentResponse>> updateEquipment(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @PathVariable Long equipmentId,
            @Valid @RequestBody UpdateEquipmentRequest request) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        EquipmentResponse response = equipmentService.updateEquipment(facilityId, equipmentId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 備品を削除する。
     */
    @DeleteMapping("/{facilityId}/equipment/{equipmentId}")
    @Operation(summary = "備品削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteEquipment(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @PathVariable Long equipmentId) {
        facilityService.findFacilityOrThrow(SCOPE_TYPE, teamId, facilityId);
        equipmentService.deleteEquipment(facilityId, equipmentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 空き状況を取得する。
     */
    @GetMapping("/{facilityId}/availability")
    @Operation(summary = "空き状況")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<AvailabilityResponse>> getAvailability(
            @PathVariable Long teamId,
            @PathVariable Long facilityId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        AvailabilityResponse response = facilityService.getAvailability(SCOPE_TYPE, teamId, facilityId, date);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
