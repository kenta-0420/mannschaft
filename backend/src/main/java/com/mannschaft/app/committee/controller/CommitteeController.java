package com.mannschaft.app.committee.controller;

import com.mannschaft.app.committee.dto.CommitteeCreateRequest;
import com.mannschaft.app.committee.dto.CommitteeMemberResponse;
import com.mannschaft.app.committee.dto.CommitteeResponse;
import com.mannschaft.app.committee.dto.CommitteeSummaryResponse;
import com.mannschaft.app.committee.dto.CommitteeStatusTransitionRequest;
import com.mannschaft.app.committee.dto.CommitteeUpdateRequest;
import com.mannschaft.app.committee.dto.UpdateMemberRoleRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.10 組織委員会コントローラー。
 * 委員会の CRUD・ステータス遷移・メンバー管理のエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "組織委員会")
@RequiredArgsConstructor
public class CommitteeController {

    private final CommitteeService committeeService;

    // ========================================
    // 委員会 CRUD
    // ========================================

    /**
     * 組織の委員会一覧を取得する。
     */
    @GetMapping("/organizations/{orgId}/committees")
    @Operation(summary = "委員会一覧取得")
    public ApiResponse<Page<CommitteeSummaryResponse>> listCommittees(
            @PathVariable Long orgId,
            @RequestParam(required = false) CommitteeStatus status,
            Pageable pageable) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        Page<CommitteeEntity> committees = committeeService.listCommittees(orgId, status, currentUserId, pageable);

        Page<CommitteeSummaryResponse> response = committees.map(c -> {
            if (c == null) {
                return null;
            }
            CommitteeRole myRole = committeeService.getCommitteeRole(c.getId(), currentUserId);
            // メンバーのみカウントを取得（非メンバーには null を返す）
            Integer memberCount = myRole != null
                    ? committeeService.getMemberCount(c.getId())
                    : null;
            return CommitteeSummaryResponse.builder()
                    .id(c.getId())
                    .name(c.getName())
                    .status(c.getStatus())
                    .visibilityToOrg(c.getVisibilityToOrg())
                    .purposeTag(c.getPurposeTag())
                    .startDate(c.getStartDate())
                    .endDate(c.getEndDate())
                    .memberCount(memberCount)
                    .myRole(myRole)
                    .createdAt(c.getCreatedAt())
                    .build();
        });

        return ApiResponse.of(response);
    }

    /**
     * 委員会を設立する。
     */
    @PostMapping("/organizations/{orgId}/committees")
    @Operation(summary = "委員会設立")
    public ResponseEntity<ApiResponse<CommitteeResponse>> createCommittee(
            @PathVariable Long orgId,
            @Valid @RequestBody CommitteeCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeEntity committee = committeeService.createCommittee(orgId, request, currentUserId);

        CommitteeRole myRole = committeeService.getCommitteeRole(committee.getId(), currentUserId);
        CommitteeResponse response = CommitteeResponse.of(committee).toBuilder()
                .myRole(myRole)
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 委員会詳細を取得する。
     */
    @GetMapping("/committees/{committeeId}")
    @Operation(summary = "委員会詳細取得")
    public ApiResponse<CommitteeResponse> getCommittee(@PathVariable Long committeeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeEntity committee = committeeService.getCommittee(committeeId, currentUserId);

        CommitteeRole myRole = committeeService.getCommitteeRole(committeeId, currentUserId);
        CommitteeResponse response = CommitteeResponse.of(committee).toBuilder()
                .myRole(myRole)
                .build();

        return ApiResponse.of(response);
    }

    /**
     * 委員会情報を更新する。
     */
    @PatchMapping("/committees/{committeeId}")
    @Operation(summary = "委員会情報更新")
    public ApiResponse<CommitteeResponse> updateCommittee(
            @PathVariable Long committeeId,
            @Valid @RequestBody CommitteeUpdateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeEntity committee = committeeService.updateCommittee(committeeId, request, currentUserId);

        CommitteeRole myRole = committeeService.getCommitteeRole(committeeId, currentUserId);
        CommitteeResponse response = CommitteeResponse.of(committee).toBuilder()
                .myRole(myRole)
                .build();

        return ApiResponse.of(response);
    }

    /**
     * ステータス遷移を実行する。
     */
    @PostMapping("/committees/{committeeId}/status")
    @Operation(summary = "委員会ステータス遷移")
    public ApiResponse<CommitteeResponse> transitionStatus(
            @PathVariable Long committeeId,
            @Valid @RequestBody CommitteeStatusTransitionRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeEntity committee = committeeService.transitionStatus(committeeId, request, currentUserId);

        CommitteeRole myRole = committeeService.getCommitteeRole(committeeId, currentUserId);
        CommitteeResponse response = CommitteeResponse.of(committee).toBuilder()
                .myRole(myRole)
                .build();

        return ApiResponse.of(response);
    }

    // ========================================
    // メンバー管理
    // ========================================

    /**
     * メンバー一覧を取得する。
     */
    @GetMapping("/committees/{committeeId}/members")
    @Operation(summary = "委員会メンバー一覧取得")
    public ApiResponse<List<CommitteeMemberResponse>> listMembers(@PathVariable Long committeeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<CommitteeMemberEntity> members = committeeService.listMembers(committeeId, currentUserId);

        List<CommitteeMemberResponse> response = members.stream()
                .map(CommitteeMemberResponse::of)
                .collect(Collectors.toList());

        return ApiResponse.of(response);
    }

    /**
     * メンバーのロールを変更する。
     */
    @PatchMapping("/committees/{committeeId}/members/{userId}")
    @Operation(summary = "委員会メンバーロール変更")
    public ApiResponse<CommitteeMemberResponse> updateMemberRole(
            @PathVariable Long committeeId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateMemberRoleRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        CommitteeMemberEntity member = committeeService.updateMemberRole(
                committeeId, userId, request.getRole(), currentUserId);

        return ApiResponse.of(CommitteeMemberResponse.of(member));
    }

    /**
     * メンバーを解任する。
     */
    @DeleteMapping("/committees/{committeeId}/members/{userId}")
    @Operation(summary = "委員会メンバー解任")
    public ResponseEntity<Void> removeMember(
            @PathVariable Long committeeId,
            @PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        committeeService.removeMember(committeeId, userId, currentUserId);

        return ResponseEntity.noContent().build();
    }

    /**
     * 委員会から自発的に離脱する。
     */
    @PostMapping("/committees/{committeeId}/members/me/leave")
    @Operation(summary = "委員会から離脱")
    public ResponseEntity<Void> leaveCommittee(@PathVariable Long committeeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        committeeService.leaveCommittee(committeeId, currentUserId);

        return ResponseEntity.noContent().build();
    }
}
