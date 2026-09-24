package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.service.ConfirmableRecipientGroupService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループ CRUD（チーム）。
 *
 * <p><b>骨格のみ（試練A補完・AC-16）。</b> {@link OrgConfirmableRecipientGroupController} と同型。
 * 本体は {@link ConfirmableRecipientGroupService} のスタブを呼ぶだけであり、出陣で実装するまで
 * {@code UnsupportedOperationException} が 500 として伝播する。ここで固定するのは<b>認可</b>
 * （SEND_NOTIFICATION 権限）のみである。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/confirmable-recipient-groups")
@Tag(name = "チーム確認通知宛先グループ", description = "F04.9 確認通知の宛先グループ CRUD（CMP-260920-1040）")
@RequiredArgsConstructor
public class TeamConfirmableRecipientGroupController {

    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final ConfirmableRecipientGroupService groupService;
    private final AccessControlService accessControlService;

    @PostMapping
    @Operation(summary = "宛先グループ作成（チーム）")
    public ResponseEntity<ApiResponse<ConfirmableRecipientGroupResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody ConfirmableRecipientGroupCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, teamId, ScopeType.TEAM.name(), SEND_NOTIFICATION);
        ConfirmableRecipientGroupResponse response =
                groupService.create(ScopeType.TEAM, teamId, currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "宛先グループ一覧取得（チーム）")
    public ResponseEntity<ApiResponse<List<ConfirmableRecipientGroupResponse>>> list(
            @PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, teamId, ScopeType.TEAM.name());
        return ResponseEntity.ok(ApiResponse.of(groupService.list(ScopeType.TEAM, teamId)));
    }

    @PutMapping("/{groupId}")
    @Operation(summary = "宛先グループ更新（チーム）")
    public ResponseEntity<ApiResponse<ConfirmableRecipientGroupResponse>> update(
            @PathVariable Long teamId,
            @PathVariable UUID groupId,
            @Valid @RequestBody ConfirmableRecipientGroupCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, teamId, ScopeType.TEAM.name(), SEND_NOTIFICATION);
        ConfirmableRecipientGroupResponse response =
                groupService.update(ScopeType.TEAM, teamId, groupId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{groupId}")
    @Operation(summary = "宛先グループ削除（チーム）")
    public ResponseEntity<Void> delete(
            @PathVariable Long teamId,
            @PathVariable UUID groupId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, teamId, ScopeType.TEAM.name(), SEND_NOTIFICATION);
        groupService.delete(ScopeType.TEAM, teamId, groupId);
        return ResponseEntity.noContent().build();
    }
}
