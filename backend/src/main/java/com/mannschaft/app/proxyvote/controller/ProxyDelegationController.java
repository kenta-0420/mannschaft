package com.mannschaft.app.proxyvote.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.proxyvote.dto.AttendanceResponse;
import com.mannschaft.app.proxyvote.dto.DelegateRequest;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.ReviewDelegationRequest;
import com.mannschaft.app.proxyvote.service.ProxyDelegationService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 委任状コントローラー。委任状の提出・取り下げ・承認/却下・出席状況APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/proxy-votes")
@Tag(name = "議決権行使・委任状", description = "F08.3 委任状管理")
@RequiredArgsConstructor
public class ProxyDelegationController {

    private final ProxyDelegationService delegationService;


    /**
     * 委任状を提出する。
     */
    @PostMapping("/{id}/delegate")
    @Operation(summary = "委任状提出")
    public ResponseEntity<ApiResponse<DelegationResponse>> delegate(
            @PathVariable Long id, @RequestBody DelegateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(delegationService.delegate(id, request, SecurityUtils.getCurrentUserId())));
    }

    /**
     * 委任状を取り下げる。
     */
    @DeleteMapping("/{id}/delegate")
    @Operation(summary = "委任状取り下げ")
    public ResponseEntity<Void> cancelDelegation(@PathVariable Long id) {
        delegationService.cancelDelegation(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 委任状を承認/却下する。
     */
    @PatchMapping("/delegations/{delegationId}/review")
    @Operation(summary = "委任状承認/却下")
    public ResponseEntity<ApiResponse<DelegationResponse>> reviewDelegation(
            @PathVariable Long delegationId, @Valid @RequestBody ReviewDelegationRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                delegationService.reviewDelegation(delegationId, request, SecurityUtils.getCurrentUserId())));
    }

    /**
     * 出席・委任状況一覧を取得する。
     */
    @GetMapping("/{id}/attendance")
    @Operation(summary = "出席・委任状況")
    public ResponseEntity<ApiResponse<AttendanceResponse>> getAttendance(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(delegationService.getAttendance(id)));
    }
}
