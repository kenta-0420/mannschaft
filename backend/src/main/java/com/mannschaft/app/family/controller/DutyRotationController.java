package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.DutyRotationRequest;
import com.mannschaft.app.family.dto.DutyRotationResponse;
import com.mannschaft.app.family.dto.DutyTodayResponse;
import com.mannschaft.app.family.service.DutyRotationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当番ローテーションコントローラー。当番管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/duties")
@Tag(name = "当番ローテーション", description = "F01.4 当番ローテーション")
@RequiredArgsConstructor
public class DutyRotationController {

    private final DutyRotationService dutyRotationService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 当番ローテーション一覧を取得する（今日の担当者付き）。
     */
    @GetMapping
    @Operation(summary = "当番ローテーション一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<DutyRotationResponse>>> getDuties(@PathVariable Long teamId) {
        return ResponseEntity.ok(dutyRotationService.getDuties(teamId));
    }

    /**
     * 当番ローテーションを作成する（ADMIN用）。
     */
    @PostMapping
    @Operation(summary = "当番ローテーション作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<DutyRotationResponse>> createDuty(
            @PathVariable Long teamId,
            @Valid @RequestBody DutyRotationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(dutyRotationService.createDuty(teamId, getCurrentUserId(), request));
    }

    /**
     * 当番ローテーションを更新する（ADMIN用）。
     */
    @PutMapping("/{id}")
    @Operation(summary = "当番ローテーション更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<DutyRotationResponse>> updateDuty(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody DutyRotationRequest request) {
        return ResponseEntity.ok(dutyRotationService.updateDuty(teamId, id, request));
    }

    /**
     * 当番ローテーションを削除する（ADMIN用）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "当番ローテーション削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteDuty(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        dutyRotationService.deleteDuty(teamId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 今日の当番一覧を取得する（ダッシュボードウィジェット用）。
     */
    @GetMapping("/today")
    @Operation(summary = "今日の当番一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<DutyTodayResponse>>> getTodayDuties(@PathVariable Long teamId) {
        return ResponseEntity.ok(dutyRotationService.getTodayDuties(teamId));
    }
}
