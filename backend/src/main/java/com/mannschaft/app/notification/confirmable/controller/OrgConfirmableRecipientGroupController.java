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
 * CMP-260920-1040 F04.9 確認通知の宛先グループ CRUD（組織）。
 *
 * <p><b>骨格のみ（試練A補完・AC-16）。</b> 本体は {@link ConfirmableRecipientGroupService} の
 * スタブを呼ぶだけであり、出陣で実装するまで {@code UnsupportedOperationException} が
 * 500 として伝播する。ここで固定するのは<b>認可</b>（SEND_NOTIFICATION 権限）のみである。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/confirmable-recipient-groups")
@Tag(name = "組織確認通知宛先グループ", description = "F04.9 確認通知の宛先グループ CRUD（CMP-260920-1040）")
@RequiredArgsConstructor
public class OrgConfirmableRecipientGroupController {

    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final ConfirmableRecipientGroupService groupService;
    private final AccessControlService accessControlService;

    @PostMapping
    @Operation(summary = "宛先グループ作成（組織）")
    public ResponseEntity<ApiResponse<ConfirmableRecipientGroupResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody ConfirmableRecipientGroupCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);
        ConfirmableRecipientGroupResponse response =
                groupService.create(ScopeType.ORGANIZATION, orgId, currentUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "宛先グループ一覧取得（組織）")
    public ResponseEntity<ApiResponse<List<ConfirmableRecipientGroupResponse>>> list(
            @PathVariable Long orgId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkMembership(currentUserId, orgId, ScopeType.ORGANIZATION.name());
        return ResponseEntity.ok(ApiResponse.of(groupService.list(ScopeType.ORGANIZATION, orgId)));
    }

    @PutMapping("/{groupId}")
    @Operation(summary = "宛先グループ更新（組織）")
    public ResponseEntity<ApiResponse<ConfirmableRecipientGroupResponse>> update(
            @PathVariable Long orgId,
            @PathVariable UUID groupId,
            @Valid @RequestBody ConfirmableRecipientGroupCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);
        ConfirmableRecipientGroupResponse response =
                groupService.update(ScopeType.ORGANIZATION, orgId, groupId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{groupId}")
    @Operation(summary = "宛先グループ削除（組織）")
    public ResponseEntity<Void> delete(
            @PathVariable Long orgId,
            @PathVariable UUID groupId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);
        groupService.delete(ScopeType.ORGANIZATION, orgId, groupId);
        return ResponseEntity.noContent().build();
    }
}
