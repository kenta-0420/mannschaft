package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.CrossRefResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.service.ScheduleCrossRefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * スケジュール招待管理コントローラー。チーム・組織のクロス招待受信・承認・拒否・最終確認APIを提供する。
 */
@RestController
@Tag(name = "スケジュール招待管理", description = "F03.1 クロスチーム・組織招待の受信管理")
@RequiredArgsConstructor
public class ScheduleInvitationController {

    private static final String TARGET_TYPE_TEAM = "TEAM";
    private static final String TARGET_TYPE_ORGANIZATION = "ORGANIZATION";

    private final ScheduleCrossRefService crossRefService;

    // --- チーム招待受信 ---

    /**
     * チーム宛の受信招待一覧を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/schedule-invitations")
    @Operation(summary = "チーム受信招待一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CrossRefResponse>>> listTeamInvitations(
            @PathVariable Long teamId) {
        List<CrossRefResponse> responses = crossRefService.listReceivedInvitations(
                TARGET_TYPE_TEAM, teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チーム宛の招待を承認する。
     */
    @PostMapping("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/accept")
    @Operation(summary = "チーム招待承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> acceptTeamInvitation(
            @PathVariable Long teamId,
            @PathVariable Long invitationId) {
        ScheduleResponse response = crossRefService.acceptInvitation(invitationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チーム宛の招待を拒否する。
     */
    @PostMapping("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/reject")
    @Operation(summary = "チーム招待拒否")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "拒否成功")
    public ResponseEntity<Void> rejectTeamInvitation(
            @PathVariable Long teamId,
            @PathVariable Long invitationId) {
        crossRefService.rejectInvitation(invitationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * チーム宛の招待を最終確認する（非公開チーム用）。
     */
    @PostMapping("/api/v1/teams/{teamId}/schedule-invitations/{invitationId}/confirm")
    @Operation(summary = "チーム招待最終確認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "確認成功")
    public ResponseEntity<Void> confirmTeamInvitation(
            @PathVariable Long teamId,
            @PathVariable Long invitationId) {
        crossRefService.confirmInvitation(invitationId);
        return ResponseEntity.noContent().build();
    }

    // --- 組織招待受信 ---

    /**
     * 組織宛の受信招待一覧を取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/schedule-invitations")
    @Operation(summary = "組織受信招待一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CrossRefResponse>>> listOrgInvitations(
            @PathVariable Long orgId) {
        List<CrossRefResponse> responses = crossRefService.listReceivedInvitations(
                TARGET_TYPE_ORGANIZATION, orgId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 組織宛の招待を承認する。
     */
    @PostMapping("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/accept")
    @Operation(summary = "組織招待承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<ScheduleResponse>> acceptOrgInvitation(
            @PathVariable Long orgId,
            @PathVariable Long invitationId) {
        ScheduleResponse response = crossRefService.acceptInvitation(invitationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 組織宛の招待を拒否する。
     */
    @PostMapping("/api/v1/organizations/{orgId}/schedule-invitations/{invitationId}/reject")
    @Operation(summary = "組織招待拒否")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "拒否成功")
    public ResponseEntity<Void> rejectOrgInvitation(
            @PathVariable Long orgId,
            @PathVariable Long invitationId) {
        crossRefService.rejectInvitation(invitationId);
        return ResponseEntity.noContent().build();
    }
}
