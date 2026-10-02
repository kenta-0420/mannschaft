package com.mannschaft.app.joinrequest.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.joinrequest.dto.JoinRequestCreateRequest;
import com.mannschaft.app.joinrequest.dto.JoinRequestResponse;
import com.mannschaft.app.joinrequest.dto.JoinRequestReviewRequest;
import com.mannschaft.app.joinrequest.entity.JoinRequestStatus;
import com.mannschaft.app.joinrequest.service.JoinRequestService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 柱③-A「MEMBER 参加申請（join request）」コントローラー（CMP-260901-1538）。
 *
 * <p>TEAM / ORGANIZATION それぞれに専用パスを持つ（金型: {@code InviteQrPdfController} の
 * {@code /api/v1/teams/{teamId}/invite-tokens/...} 流儀。{@code scopeType} をパス変数化して
 * 単一マッピングに統合すると Spring のパスマッチングが曖昧になりやすいため避ける）。
 * いずれも同一の {@link JoinRequestService} へ scopeType 文字列を渡して委譲するだけで、
 * ロジックの二重実装はしない。</p>
 *
 * <p>認可は Service 層で完結する（{@link AuthorizedInService}・金型:
 * {@code VillageJoinRequestController}）。</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "柱③-A 参加申請", description = "PUBLIC な TEAM/ORGANIZATION への MEMBER 参加申請の受付・審査API")
public class JoinRequestController {

    private static final String TEAM = "TEAM";
    private static final String ORGANIZATION = "ORGANIZATION";

    private final JoinRequestService service;

    // ------------------------------------------------------------------
    // TEAM
    // ------------------------------------------------------------------

    @AuthorizedInService
    @PostMapping("/api/v1/teams/{teamId}/join-requests")
    @Operation(summary = "チームへ参加申請を行う（PUBLIC な ACTIVE チームのみ）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> createForTeam(
            @PathVariable("teamId") Long teamId,
            @Valid @RequestBody(required = false) JoinRequestCreateRequest request) {
        return create(TEAM, teamId, request);
    }

    @SelfScopedEndpoint(
            "パス・クエリで対象ユーザーを一切受け取らず、SecurityUtils.getCurrentUserId() が解決した"
            + "認証済みユーザーIDのみを検索条件に使う（JoinRequestService#listMine が"
            + "requesterUserId で絞り込む）。他人の識別子を指定する余地が構造的に無い。")
    @GetMapping("/api/v1/teams/{teamId}/join-requests/me")
    @Operation(summary = "自分のチーム参加申請一覧（申請者本人）")
    public ResponseEntity<ApiResponse<List<JoinRequestResponse>>> listMineForTeam(
            @PathVariable("teamId") Long teamId) {
        return listMine(TEAM, teamId);
    }

    @AuthorizedInService
    @GetMapping("/api/v1/teams/{teamId}/join-requests")
    @Operation(summary = "チームの参加申請一覧（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<Page<JoinRequestResponse>>> listForTeam(
            @PathVariable("teamId") Long teamId,
            @RequestParam(value = "status", required = false) JoinRequestStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return list(TEAM, teamId, status, page, size);
    }

    @AuthorizedInService
    @PostMapping("/api/v1/teams/{teamId}/join-requests/{id}/approve")
    @Operation(summary = "チーム参加申請を承認（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> approveForTeam(
            @PathVariable("teamId") Long teamId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) JoinRequestReviewRequest review) {
        return approve(TEAM, teamId, id, review);
    }

    @AuthorizedInService
    @PostMapping("/api/v1/teams/{teamId}/join-requests/{id}/reject")
    @Operation(summary = "チーム参加申請を却下（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> rejectForTeam(
            @PathVariable("teamId") Long teamId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) JoinRequestReviewRequest review) {
        return reject(TEAM, teamId, id, review);
    }

    // ------------------------------------------------------------------
    // ORGANIZATION
    // ------------------------------------------------------------------

    @AuthorizedInService
    @PostMapping("/api/v1/organizations/{organizationId}/join-requests")
    @Operation(summary = "組織へ参加申請を行う（PUBLIC な ACTIVE 組織のみ）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> createForOrganization(
            @PathVariable("organizationId") Long organizationId,
            @Valid @RequestBody(required = false) JoinRequestCreateRequest request) {
        return create(ORGANIZATION, organizationId, request);
    }

    @SelfScopedEndpoint(
            "パス・クエリで対象ユーザーを一切受け取らず、SecurityUtils.getCurrentUserId() が解決した"
            + "認証済みユーザーIDのみを検索条件に使う（JoinRequestService#listMine が"
            + "requesterUserId で絞り込む）。他人の識別子を指定する余地が構造的に無い。")
    @GetMapping("/api/v1/organizations/{organizationId}/join-requests/me")
    @Operation(summary = "自分の組織参加申請一覧（申請者本人）")
    public ResponseEntity<ApiResponse<List<JoinRequestResponse>>> listMineForOrganization(
            @PathVariable("organizationId") Long organizationId) {
        return listMine(ORGANIZATION, organizationId);
    }

    @AuthorizedInService
    @GetMapping("/api/v1/organizations/{organizationId}/join-requests")
    @Operation(summary = "組織の参加申請一覧（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<Page<JoinRequestResponse>>> listForOrganization(
            @PathVariable("organizationId") Long organizationId,
            @RequestParam(value = "status", required = false) JoinRequestStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return list(ORGANIZATION, organizationId, status, page, size);
    }

    @AuthorizedInService
    @PostMapping("/api/v1/organizations/{organizationId}/join-requests/{id}/approve")
    @Operation(summary = "組織参加申請を承認（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> approveForOrganization(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) JoinRequestReviewRequest review) {
        return approve(ORGANIZATION, organizationId, id, review);
    }

    @AuthorizedInService
    @PostMapping("/api/v1/organizations/{organizationId}/join-requests/{id}/reject")
    @Operation(summary = "組織参加申請を却下（ADMIN/DEPUTY_ADMIN）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> rejectForOrganization(
            @PathVariable("organizationId") Long organizationId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) JoinRequestReviewRequest review) {
        return reject(ORGANIZATION, organizationId, id, review);
    }

    // ------------------------------------------------------------------
    // 共通実装（scopeType 文字列を渡すだけで委譲。ロジックの二重実装はしない）
    // ------------------------------------------------------------------

    private ResponseEntity<ApiResponse<JoinRequestResponse>> create(
            String scopeType, Long scopeId, JoinRequestCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.createRequest(scopeType, scopeId, actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    private ResponseEntity<ApiResponse<List<JoinRequestResponse>>> listMine(String scopeType, Long scopeId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<JoinRequestResponse> result = service.listMine(scopeType, scopeId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    private ResponseEntity<ApiResponse<Page<JoinRequestResponse>>> list(
            String scopeType, Long scopeId, JoinRequestStatus status, int page, int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Page<JoinRequestResponse> result =
                service.listForReviewers(scopeType, scopeId, actorUserId, status, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    private ResponseEntity<ApiResponse<JoinRequestResponse>> approve(
            String scopeType, Long scopeId, UUID id, JoinRequestReviewRequest review) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.approve(scopeType, scopeId, id, actorUserId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    private ResponseEntity<ApiResponse<JoinRequestResponse>> reject(
            String scopeType, Long scopeId, UUID id, JoinRequestReviewRequest review) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.reject(scopeType, scopeId, id, actorUserId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
