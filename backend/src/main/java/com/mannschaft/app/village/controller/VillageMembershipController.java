package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.MembershipBanRequest;
import com.mannschaft.app.village.dto.MembershipJoinRequest;
import com.mannschaft.app.village.dto.MembershipListResponse;
import com.mannschaft.app.village.dto.MembershipResponse;
import com.mannschaft.app.village.dto.RoleChangeRequest;
import com.mannschaft.app.village.service.VillageMembershipService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 Phase 1 B3 — 村メンバーシップ Controller。
 *
 * <p>担当 API（出陣指示書 §4.3）:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{vid}/memberships} — 参加</li>
 *   <li>{@code DELETE /api/v1/villages/{vid}/memberships/{membershipId}} — 退出</li>
 *   <li>{@code GET    /api/v1/villages/{vid}/memberships} — 一覧</li>
 *   <li>{@code PATCH  /api/v1/villages/{vid}/memberships/{membershipId}/role} — ロール変更</li>
 *   <li>{@code POST   /api/v1/villages/{vid}/memberships/{membershipId}/ban} — BAN</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/memberships")
@Tag(name = "村メンバーシップ (F17.1)",
     description = "Phase 1: 村への参加・退出・ロール変更・BAN")
@RequiredArgsConstructor
@AuthorizedInService
public class VillageMembershipController {

    private final VillageMembershipService membershipService;

    @PostMapping
    @Operation(summary = "村に参加する（FREE 村のみ即時参加可）")
    public ResponseEntity<ApiResponse<MembershipResponse>> join(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody MembershipJoinRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MembershipResponse response = membershipService.join(villageId, actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/{membershipId}")
    @Operation(summary = "村から退出する（HEADMAN は自動的に後継者へ引き継ぎ）")
    public ResponseEntity<Void> leave(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("membershipId") UUID membershipId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        membershipService.leave(villageId, membershipId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "村メンバー一覧を取得（村人のみ）")
    public ApiResponse<MembershipListResponse> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MembershipListResponse response = membershipService.listMembers(villageId, actorUserId, page, size);
        return ApiResponse.of(response);
    }

    @PatchMapping("/{membershipId}/role")
    @Operation(summary = "村内ロールを変更（HEADMAN のみ実行可）")
    public ApiResponse<MembershipResponse> changeRole(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("membershipId") UUID membershipId,
            @Valid @RequestBody RoleChangeRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MembershipResponse response = membershipService.changeRole(villageId, membershipId, actorUserId, request);
        return ApiResponse.of(response);
    }

    @PostMapping("/{membershipId}/ban")
    @Operation(summary = "村メンバーを BAN する（HEADMAN のみ）")
    public ApiResponse<MembershipResponse> ban(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("membershipId") UUID membershipId,
            @Valid @RequestBody(required = false) MembershipBanRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        MembershipResponse response = membershipService.ban(villageId, membershipId, actorUserId, request);
        return ApiResponse.of(response);
    }
}
