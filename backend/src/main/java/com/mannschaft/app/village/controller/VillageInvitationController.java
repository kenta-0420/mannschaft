package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.VillageInvitationAcceptResponse;
import com.mannschaft.app.village.dto.VillageInvitationCreateRequest;
import com.mannschaft.app.village.dto.VillageInvitationIssueResponse;
import com.mannschaft.app.village.dto.VillageInvitationSummary;
import com.mannschaft.app.village.service.VillageInvitationService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 村招待 Controller（<b>骨格スタブ</b>）。試練が HTTP 契約を書けるように経路だけを用意する。
 *
 * <p>受諾 EP は<b>パスに村IDを含めない</b>。村IDを置くと、その存在自体が
 * 「この村は実在する」という手掛かりになり、非公開村の秘匿が破れるためである。</p>
 *
 * <p>各メソッドの中身は {@link VillageInvitationService} のスタブに委譲され、
 * 現時点では {@link UnsupportedOperationException} を投げる。</p>
 */
@RestController
@Tag(name = "村招待 (F17.1)", description = "非公開村への招待の発行・失効・受諾")
@RequiredArgsConstructor
@AuthorizedInService
public class VillageInvitationController {

    private final VillageInvitationService invitationService;

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "Village基盤の招待発行は既存17機能Gateの対象外であるため")
    @PostMapping("/api/v1/villages/{villageId}/invitations")
    @Operation(summary = "招待を発行する（村長・長老のみ）")
    public ResponseEntity<ApiResponse<VillageInvitationIssueResponse>> issue(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody VillageInvitationCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(invitationService.issue(villageId, actorUserId, request)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "Village基盤の招待管理は既存17機能Gateの対象外であるため")
    @GetMapping("/api/v1/villages/{villageId}/invitations")
    @Operation(summary = "自村の招待一覧（村長・長老のみ）")
    public ApiResponse<List<VillageInvitationSummary>> list(
            @PathVariable("villageId") UUID villageId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(invitationService.list(villageId, actorUserId));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "発行済みVillage招待の取消経路を常に維持するため")
    @DeleteMapping("/api/v1/villages/{villageId}/invitations/{invitationId}")
    @Operation(summary = "招待を失効させる（冪等）")
    public ResponseEntity<Void> revoke(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("invitationId") UUID invitationId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        invitationService.revoke(villageId, invitationId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "発行済みVillage招待の受諾経路を常に維持するため")
    @PostMapping("/api/v1/village-invitations/{token}/accept")
    @Operation(summary = "招待を受諾して村人になる（認証必須）")
    public ResponseEntity<ApiResponse<VillageInvitationAcceptResponse>> accept(
            @PathVariable("token") String token) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(invitationService.accept(token, actorUserId)));
    }
}
