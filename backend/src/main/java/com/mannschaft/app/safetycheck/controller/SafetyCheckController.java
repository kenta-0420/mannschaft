package com.mannschaft.app.safetycheck.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.safetycheck.dto.BulkRespondRequest;
import com.mannschaft.app.safetycheck.dto.CreateSafetyCheckRequest;
import com.mannschaft.app.safetycheck.dto.RespondRequest;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResultsResponse;
import com.mannschaft.app.safetycheck.dto.SafetyPresetResponse;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.dto.UnrespondedUserResponse;
import com.mannschaft.app.safetycheck.service.SafetyCheckService;
import com.mannschaft.app.safetycheck.service.SafetyPresetService;
import com.mannschaft.app.safetycheck.service.SafetyResponseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 安否確認コントローラー。安否確認の発信・回答・結果取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/safety-checks")
@Tag(name = "安否確認管理", description = "F03.6 緊急安否確認")
@RequiredArgsConstructor
public class SafetyCheckController {

    private final SafetyCheckService safetyCheckService;
    private final SafetyResponseService responseService;
    private final SafetyPresetService presetService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 安否確認を発信する。
     */
    @PostMapping
    @Operation(summary = "安否確認発信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "発信成功")
    public ResponseEntity<ApiResponse<SafetyCheckResponse>> createSafetyCheck(
            @Valid @RequestBody CreateSafetyCheckRequest request) {
        SafetyCheckResponse response = safetyCheckService.createSafetyCheck(request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 安否確認一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "安否確認一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SafetyCheckResponse>> listSafetyChecks(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SafetyCheckResponse> result = safetyCheckService.listSafetyChecks(
                scopeType, scopeId, status, page, size);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 安否確認詳細を取得する。
     */
    @GetMapping("/{safetyCheckId}")
    @Operation(summary = "安否確認詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SafetyCheckResponse>> getSafetyCheck(
            @PathVariable Long safetyCheckId) {
        SafetyCheckResponse response = safetyCheckService.getSafetyCheck(safetyCheckId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 安否確認をクローズする。
     */
    @PostMapping("/{safetyCheckId}/close")
    @Operation(summary = "安否確認クローズ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "クローズ成功")
    public ResponseEntity<ApiResponse<SafetyCheckResponse>> closeSafetyCheck(
            @PathVariable Long safetyCheckId) {
        SafetyCheckResponse response = safetyCheckService.closeSafetyCheck(safetyCheckId, getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 安否確認に回答する。
     */
    @PostMapping("/{safetyCheckId}/respond")
    @Operation(summary = "安否確認回答")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "回答成功")
    public ResponseEntity<ApiResponse<SafetyResponseResponse>> respond(
            @PathVariable Long safetyCheckId,
            @Valid @RequestBody RespondRequest request) {
        SafetyResponseResponse response = responseService.respond(
                safetyCheckId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 安否確認に一括回答する（管理者用）。
     */
    @PostMapping("/{safetyCheckId}/respond/bulk")
    @Operation(summary = "安否確認一括回答")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "一括回答成功")
    public ResponseEntity<ApiResponse<List<SafetyResponseResponse>>> bulkRespond(
            @PathVariable Long safetyCheckId,
            @Valid @RequestBody BulkRespondRequest request) {
        List<SafetyResponseResponse> responses = responseService.bulkRespond(safetyCheckId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(responses));
    }

    /**
     * 安否確認の結果集計を取得する。
     */
    @GetMapping("/{safetyCheckId}/results")
    @Operation(summary = "安否確認結果集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SafetyCheckResultsResponse>> getResults(
            @PathVariable Long safetyCheckId) {
        SafetyCheckResultsResponse response = safetyCheckService.getResults(safetyCheckId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 未回答ユーザー一覧を取得する。
     */
    @GetMapping("/{safetyCheckId}/unresponded")
    @Operation(summary = "未回答ユーザー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<UnrespondedUserResponse>>> getUnrespondedUsers(
            @PathVariable Long safetyCheckId) {
        List<UnrespondedUserResponse> responses = safetyCheckService.getUnrespondedUsers(safetyCheckId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 安否確認履歴を取得する（クローズ済み）。
     */
    @GetMapping("/history")
    @Operation(summary = "安否確認履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SafetyCheckResponse>> getHistory(
            @RequestParam String scopeType,
            @RequestParam Long scopeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SafetyCheckResponse> result = safetyCheckService.getHistory(scopeType, scopeId, page, size);
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * メッセージプリセット一覧を取得する（回答時の選択肢）。
     */
    @GetMapping("/presets")
    @Operation(summary = "メッセージプリセット一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SafetyPresetResponse>>> listPresets() {
        List<SafetyPresetResponse> presets = presetService.listActivePresets();
        return ResponseEntity.ok(ApiResponse.of(presets));
    }

    /**
     * リマインドを送信する。
     */
    @PostMapping("/{safetyCheckId}/remind")
    @Operation(summary = "リマインド送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "送信成功")
    public ResponseEntity<Void> sendReminder(@PathVariable Long safetyCheckId) {
        safetyCheckService.sendReminder(safetyCheckId, getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
