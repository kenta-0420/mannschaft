package com.mannschaft.app.committee.controller;

import com.mannschaft.app.committee.dto.CommitteeInvitationResponse;
import com.mannschaft.app.committee.dto.CommitteeInviteRequest;
import com.mannschaft.app.committee.dto.CommitteeMemberResponse;
import com.mannschaft.app.committee.dto.InviteByTokenRequest;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.service.CommitteeInvitationService;
import com.mannschaft.app.committee.service.CommitteeInvitationService.SendInvitationsResult;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.10 組織委員会招集コントローラー。
 * 招集状の送付・一覧・取り下げ・受諾・辞退のエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "組織委員会招集")
@RequiredArgsConstructor
public class CommitteeInvitationController {

    private final CommitteeInvitationService committeeInvitationService;

    /**
     * 招集状送付レスポンス。送付した招集状リストとスキップ件数を返す。
     */
    @Getter
    public static class SendInvitationsResponse {
        private final List<CommitteeInvitationResponse> invitations;
        private final int skippedExistingMemberCount;
        private final int skippedExistingInvitationCount;

        public SendInvitationsResponse(List<CommitteeInvitationResponse> invitations,
                                        int skippedExistingMemberCount,
                                        int skippedExistingInvitationCount) {
            this.invitations = invitations;
            this.skippedExistingMemberCount = skippedExistingMemberCount;
            this.skippedExistingInvitationCount = skippedExistingInvitationCount;
        }
    }

    /**
     * 招集状を送付する。
     * POST /committees/{committeeId}/invitations
     */
    @PostMapping("/committees/{committeeId}/invitations")
    @Operation(summary = "委員会招集状送付")
    public ResponseEntity<ApiResponse<SendInvitationsResponse>> sendInvitations(
            @PathVariable Long committeeId,
            @Valid @RequestBody CommitteeInviteRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        SendInvitationsResult result = committeeInvitationService.sendInvitations(committeeId, request, currentUserId);

        List<CommitteeInvitationResponse> invitations = result.sentInvitations().stream()
                .map(CommitteeInvitationResponse::of)
                .collect(Collectors.toList());

        SendInvitationsResponse response = new SendInvitationsResponse(
                invitations,
                result.skippedExistingMemberCount(),
                result.skippedExistingInvitationCount()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 招集中一覧を取得する。
     * GET /committees/{committeeId}/invitations
     */
    @GetMapping("/committees/{committeeId}/invitations")
    @Operation(summary = "委員会招集中一覧取得")
    public ApiResponse<List<CommitteeInvitationResponse>> listPendingInvitations(
            @PathVariable Long committeeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<CommitteeInvitationEntity> invitations =
                committeeInvitationService.listPendingInvitations(committeeId, currentUserId);

        List<CommitteeInvitationResponse> response = invitations.stream()
                .map(CommitteeInvitationResponse::of)
                .collect(Collectors.toList());

        return ApiResponse.of(response);
    }

    /**
     * 招集状を取り下げる。
     * DELETE /committee-invitations/{invitationId}
     */
    @DeleteMapping("/committee-invitations/{invitationId}")
    @Operation(summary = "委員会招集状取り下げ")
    public ResponseEntity<Void> cancelInvitation(@PathVariable Long invitationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        committeeInvitationService.cancelInvitation(invitationId, currentUserId);

        return ResponseEntity.noContent().build();
    }

    /**
     * 招集状をトークンで受諾する。
     * POST /committee-invitations/accept-by-token
     */
    @PostMapping("/committee-invitations/accept-by-token")
    @Operation(summary = "委員会招集受諾（トークン）")
    public ApiResponse<CommitteeMemberResponse> acceptInvitation(
            @Valid @RequestBody InviteByTokenRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeMemberEntity member =
                committeeInvitationService.acceptInvitation(request.getInviteToken(), currentUserId);

        return ApiResponse.of(CommitteeMemberResponse.of(member));
    }

    /**
     * 招集状をトークンで辞退する。
     * POST /committee-invitations/decline-by-token
     */
    @PostMapping("/committee-invitations/decline-by-token")
    @Operation(summary = "委員会招集辞退（トークン）")
    public ApiResponse<Void> declineInvitation(@Valid @RequestBody InviteByTokenRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        committeeInvitationService.declineInvitation(request.getInviteToken(), currentUserId);

        return ApiResponse.of(null);
    }
}
