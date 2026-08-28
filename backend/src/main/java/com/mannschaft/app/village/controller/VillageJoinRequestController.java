package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.village.dto.JoinRequestCreateRequest;
import com.mannschaft.app.village.dto.JoinRequestResponse;
import com.mannschaft.app.village.dto.JoinRequestReviewRequest;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.service.VillageJoinRequestService;
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
 * 村参加申請コントローラー（F17.1 Phase 1 B6 / 設計書 §4.4.4）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/villages/{villageId}/join-requests} 参加申請（認証ユーザー）</li>
 *   <li>{@code GET  /api/v1/villages/{villageId}/join-requests/me} 自分の申請一覧（申請者本人）</li>
 *   <li>{@code GET  /api/v1/villages/{villageId}/join-requests}  村長/長老用 一覧</li>
 *   <li>{@code POST /api/v1/villages/{villageId}/join-requests/{id}/approve}  承認</li>
 *   <li>{@code POST /api/v1/villages/{villageId}/join-requests/{id}/reject}   拒否</li>
 *   <li>{@code POST /api/v1/villages/{villageId}/join-requests/{id}/withdraw} 取下げ</li>
 * </ul>
 *
 * <p>SYSTEM_ADMIN 経路を含まないため、{@code AccessControlService} は使用しない。
 * 審査権限（HEADMAN / ELDER）の検証は Service 層が村ドメイン内の membership を参照して行う。</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "F17.1 村機能 - 村参加申請", description = "APPROVAL 村への参加申請の受付・審査API")
public class VillageJoinRequestController {

    private final VillageJoinRequestService service;

    // ------------------------------------------------------------------
    // 申請（申請者向け）
    // ------------------------------------------------------------------

    @AuthorizedInService
    @PostMapping("/api/v1/villages/{villageId}/join-requests")
    @Operation(summary = "村参加申請を行う（APPROVAL 村のみ）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody JoinRequestCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.createRequest(villageId, actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    // ------------------------------------------------------------------
    // 自分の申請（申請者向け）
    // ------------------------------------------------------------------

    /**
     * 申請者が自分の参加申請を取得する。
     *
     * <p>審査者向け一覧（{@link #list}）は HEADMAN/ELDER 限定のため、申請者（＝非メンバー）は
     * 自分の申請状態すら確認できず、取下げに必要な id も復元できなかった。本 EP がそれを解消する。</p>
     *
     * <p><b>IDOR 閉塞</b>: 「誰の申請を返すか」をパス・クエリで一切受け取らず、
     * {@link SecurityUtils#getCurrentUserId()} だけで解決する。したがって他人の申請を
     * 要求する余地が構造的に存在しない（403/404 の判定自体が不要）。</p>
     */
    @SelfScopedEndpoint(
            "パス・クエリで対象ユーザーを一切受け取らず、SecurityUtils.getCurrentUserId() が解決した"
            + "認証済みユーザーIDのみを検索条件に使う（VillageJoinRequestService#listMine が"
            + "requesterUserId で絞り込む）。他人の識別子を指定する余地が構造的に無い"
            + "（設計書 F17.1_village_community.md §4.4.4 表）。")
    @GetMapping("/api/v1/villages/{villageId}/join-requests/me")
    @Operation(summary = "自分の村参加申請一覧（申請者本人）")
    public ResponseEntity<ApiResponse<List<JoinRequestResponse>>> listMine(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        List<JoinRequestResponse> result = service.listMine(villageId, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    // ------------------------------------------------------------------
    // 一覧（村長/長老向け）
    // ------------------------------------------------------------------

    @AuthorizedInService
    @GetMapping("/api/v1/villages/{villageId}/join-requests")
    @Operation(summary = "村の参加申請一覧（村長/長老）")
    public ResponseEntity<ApiResponse<Page<JoinRequestResponse>>> list(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(value = "status", required = false) VillageRequestStatus status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Page<JoinRequestResponse> result = service.listForReviewers(villageId, actorUserId, status, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    // ------------------------------------------------------------------
    // 審査・取下げ
    // ------------------------------------------------------------------

    @AuthorizedInService
    @PostMapping("/api/v1/villages/{villageId}/join-requests/{id}/approve")
    @Operation(summary = "参加申請を承認（村長/長老）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> approve(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody(required = false) JoinRequestReviewRequest review) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.approve(villageId, id, actorUserId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @AuthorizedInService
    @PostMapping("/api/v1/villages/{villageId}/join-requests/{id}/reject")
    @Operation(summary = "参加申請を拒否（村長/長老）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> reject(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody JoinRequestReviewRequest review) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.reject(villageId, id, actorUserId, review);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @AuthorizedInService
    @PostMapping("/api/v1/villages/{villageId}/join-requests/{id}/withdraw")
    @Operation(summary = "参加申請を取下げ（申請者本人）")
    public ResponseEntity<ApiResponse<JoinRequestResponse>> withdraw(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("id") UUID id) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        JoinRequestResponse response = service.withdraw(villageId, id, actorUserId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
