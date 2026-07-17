package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.event.dto.CreateRegistrationRequest;
import com.mannschaft.app.event.dto.GuestRegistrationRequest;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.service.EventRegistrationService;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
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
    private final EventScopeAccessGuard eventScopeAccessGuard;

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
        // IDOR 根治: eventId のフラットサブリソースはイベント自身のスコープでメンバー判定する。
        eventScopeAccessGuard.requireMemberByEventId(SecurityUtils.getCurrentUserId(), eventId);
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
        eventScopeAccessGuard.requireMemberByEventId(SecurityUtils.getCurrentUserId(), eventId);
        // 親子BOLA根治: registrationId が eventId に属するかは Service 側で突合し、越境は404秘匿する。
        RegistrationResponse response = registrationService.getRegistration(eventId, registrationId);
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
        Long userId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireMemberByEventId(userId, eventId);
        RegistrationResponse response = registrationService.createRegistration(eventId, userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ゲストの参加登録を作成する。
     *
     * <p>認可: 招待トークン自体が認可証（{@code EventRegistrationService} が
     * トークン有効性・eventId 帰属を検証する）であり、スコープメンバーシップは要求しない
     * （ゲストは定義上スコープ非メンバーのため）。</p>
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
        Long userId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireAdminByEventId(userId, eventId);
        RegistrationResponse response = registrationService.approveRegistration(eventId, registrationId, userId);
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
        Long userId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireAdminByEventId(userId, eventId);
        RegistrationResponse response = registrationService.rejectRegistration(eventId, registrationId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 参加登録をキャンセルする。
     *
     * <p>認可: 少なくとも当該イベントスコープのメンバーであることを要求したうえで、
     * 本人（登録者）または ADMIN/DEPUTY_ADMIN のみが実際にキャンセルできる
     * （所有者判定は {@code EventRegistrationService} 側で行う）。</p>
     */
    @PostMapping("/{registrationId}/cancel")
    @Operation(summary = "参加登録キャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<RegistrationResponse>> cancelRegistration(
            @PathVariable Long eventId,
            @PathVariable Long registrationId,
            @RequestParam(required = false) String reason) {
        Long userId = SecurityUtils.getCurrentUserId();
        eventScopeAccessGuard.requireMemberByEventId(userId, eventId);
        RegistrationResponse response = registrationService.cancelRegistration(eventId, registrationId, userId, reason);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
