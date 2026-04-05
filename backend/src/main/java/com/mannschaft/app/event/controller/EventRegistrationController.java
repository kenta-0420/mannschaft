package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.event.dto.CreateRegistrationRequest;
import com.mannschaft.app.event.dto.GuestRegistrationRequest;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.service.EventRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * イベント参加登録コントローラー。参加登録のCRUD・承認・却下APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/registrations")
@Tag(name = "イベント参加登録", description = "F03.8 参加登録CRUD・承認管理")
@RequiredArgsConstructor
public class EventRegistrationController {

    private final EventRegistrationService registrationService;


    /**
     * 参加登録一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "参加登録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<RegistrationResponse>> listRegistrations(
            @PathVariable Long eventId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<RegistrationResponse> result = registrationService.listRegistrations(
                eventId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 参加登録詳細を取得する。
     */
    @GetMapping("/{registrationId}")
    @Operation(summary = "参加登録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> getRegistration(
            @PathVariable Long eventId,
            @PathVariable Long registrationId) {
        RegistrationResponse response = registrationService.getRegistration(registrationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 会員の参加登録を作成する。
     */
    @PostMapping
    @Operation(summary = "参加登録作成（会員）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> createRegistration(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateRegistrationRequest request) {
        RegistrationResponse response = registrationService.createRegistration(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ゲストの参加登録を作成する。
     */
    @PostMapping("/guest")
    @Operation(summary = "ゲスト参加登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> createGuestRegistration(
            @PathVariable Long eventId,
            @Valid @RequestBody GuestRegistrationRequest request) {
        RegistrationResponse response = registrationService.createGuestRegistration(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 参加登録を承認する。
     */
    @PostMapping("/{registrationId}/approve")
    @Operation(summary = "参加登録承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> approveRegistration(
            @PathVariable Long eventId,
            @PathVariable Long registrationId) {
        RegistrationResponse response = registrationService.approveRegistration(
                registrationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 参加登録を却下する。
     */
    @PostMapping("/{registrationId}/reject")
    @Operation(summary = "参加登録却下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "却下成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> rejectRegistration(
            @PathVariable Long eventId,
            @PathVariable Long registrationId) {
        RegistrationResponse response = registrationService.rejectRegistration(
                registrationId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 参加登録をキャンセルする。
     */
    @PostMapping("/{registrationId}/cancel")
    @Operation(summary = "参加登録キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> cancelRegistration(
            @PathVariable Long eventId,
            @PathVariable Long registrationId,
            @RequestParam(required = false) String reason) {
        RegistrationResponse response = registrationService.cancelRegistration(registrationId, reason);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
