package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestResponse;
import com.mannschaft.app.village.dto.VillageCreationRequestReviewRequest;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.service.VillageCreationRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.UUID;

/**
 * 村作成申請コントローラー（F17.1 Phase 1 B5）。
 *
 * <ul>
 *   <li>{@code POST   /api/v1/villages/creation-requests} 申請（認証ユーザー）</li>
 *   <li>{@code GET    /api/v1/me/village-creation-requests} 自分の申請一覧</li>
 *   <li>{@code GET    /api/v1/admin/village-creation-requests} 運営一覧（SYSTEM_ADMIN）</li>
 *   <li>{@code POST   /api/v1/admin/village-creation-requests/{id}/approve} 承認</li>
 *   <li>{@code POST   /api/v1/admin/village-creation-requests/{id}/reject} 拒否</li>
 *   <li>{@code POST   /api/v1/admin/village-creation-requests/{id}/withdraw} 取り下げ
 *       （申請者本人または SYSTEM_ADMIN）</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "F17.1 村機能 - 村作成申請", description = "村作成申請の受付・審査API")
public class VillageCreationRequestController {

    private final VillageCreationRequestService service;
    private final AccessControlService accessControlService;

    // ------------------------------------------------------------------
    // 申請者向け
    // ------------------------------------------------------------------

    /**
     * 村作成申請を行う。
     *
     * <p>認可は {@link VillageCreationRequestService#createRequest} 内で実施する。申請者は常に
     * 認証主体で固定され、{@code OFFICIAL} 種別の村は SYSTEM_ADMIN のみが申請できる
     * （{@code OFFICIAL_VILLAGE_FORBIDDEN}）。あわせて 24 時間のレートリミットを適用する。</p>
     */
    @AuthorizedInService
    @PostMapping("/api/v1/villages/creation-requests")
    @Operation(summary = "村作成申請を行う（OFFICIAL は SYSTEM_ADMIN のみ）")
    public ResponseEntity<ApiResponse<VillageCreationRequestResponse>> create(
            @Valid @RequestBody VillageCreationRequestCreateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        VillageCreationRequestResponse response = service.createRequest(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @SelfScopedEndpoint("検索条件が SecurityUtils.getCurrentUserId() のみで、"
            + "リクエストは他ユーザーの識別子を受け取らない"
            + "（VillageCreationRequestService#listMine が requesterUserId を認証主体で固定する）")
    @GetMapping("/api/v1/me/village-creation-requests")
    @Operation(summary = "自分の村作成申請一覧")
    public ResponseEntity<ApiResponse<List<VillageCreationRequestResponse>>> listMine() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<VillageCreationRequestResponse> list = service.listMine(userId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    // ------------------------------------------------------------------
    // 運営向け
    // ------------------------------------------------------------------

    @GetMapping("/api/v1/admin/village-creation-requests")
    @Operation(summary = "運営: 村作成申請一覧")
    public ResponseEntity<ApiResponse<Page<VillageCreationRequestResponse>>> listForAdmin(
            @RequestParam(required = false) VillageRequestStatus status,
            Pageable pageable) {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        Page<VillageCreationRequestResponse> page = service.listForAdmin(status, pageable);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @PostMapping("/api/v1/admin/village-creation-requests/{id}/approve")
    @Operation(summary = "運営: 村作成申請を承認")
    public ResponseEntity<ApiResponse<VillageCreationRequestResponse>> approve(
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) VillageCreationRequestReviewRequest review) {
        Long reviewerId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(reviewerId);
        VillageCreationRequestResponse response = service.approve(id, reviewerId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/api/v1/admin/village-creation-requests/{id}/reject")
    @Operation(summary = "運営: 村作成申請を拒否")
    public ResponseEntity<ApiResponse<VillageCreationRequestResponse>> reject(
            @PathVariable("id") UUID id,
            @Valid @RequestBody VillageCreationRequestReviewRequest review) {
        Long reviewerId = SecurityUtils.getCurrentUserId();
        accessControlService.checkSystemAdmin(reviewerId);
        VillageCreationRequestResponse response = service.reject(id, reviewerId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 村作成申請を取り下げる。
     *
     * <p>認可は {@link VillageCreationRequestService#withdraw} 内で実施する。申請エンティティを
     * 先に取得し、その {@code requesterUserId} が認証主体と一致するか、または操作者が
     * SYSTEM_ADMIN である場合にのみ通す。</p>
     */
    @AuthorizedInService
    @PostMapping("/api/v1/admin/village-creation-requests/{id}/withdraw")
    @Operation(summary = "村作成申請を取り下げ（申請者本人または運営）")
    public ResponseEntity<ApiResponse<VillageCreationRequestResponse>> withdraw(
            @PathVariable("id") UUID id) {
        Long actorId = SecurityUtils.getCurrentUserId();
        VillageCreationRequestResponse response = service.withdraw(id, actorId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
