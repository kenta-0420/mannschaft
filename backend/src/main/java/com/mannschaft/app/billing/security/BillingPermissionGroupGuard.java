package com.mannschaft.app.billing.security;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.role.entity.PermissionEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.repository.PermissionGroupPermissionRepository;
import com.mannschaft.app.role.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** 課金権限グループを使った自己昇格を、既存の汎用権限管理経路で防止する。 */
@Component
@RequiredArgsConstructor
public class BillingPermissionGroupGuard {

    private static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";
    private static final String ORGANIZATION_PERMISSION = "MANAGE_ORGANIZATION_BILLING";
    private static final String DENIED_EVENT = "BILLING_PERMISSION_GROUP_DENIED";

    private final AccessControlService accessControlService;
    private final PermissionRepository permissionRepository;
    private final PermissionGroupPermissionRepository groupPermissionRepository;
    private final AuditLogService auditLogService;

    public enum Operation {
        CREATE,
        UPDATE,
        DELETE,
        DUPLICATE,
        ASSIGN
    }

    /**
     * 旧値又は新値に課金権限があれば、同一 scope の厳密 ADMIN だけを許可する。
     *
     * @return 課金権限を含む保護対象操作なら true
     */
    public boolean authorizeMutation(
            Operation operation,
            Long actorUserId,
            Long scopeId,
            String scopeType,
            Long groupId,
            Collection<Long> oldPermissionIds,
            Collection<Long> newPermissionIds) {
        List<Long> permissionIds = Stream.concat(
                        safe(oldPermissionIds).stream(), safe(newPermissionIds).stream())
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!containsBillingPermission(permissionIds)) {
            return false;
        }
        if (!accessControlService.isAdmin(actorUserId, scopeId, scopeType)) {
            audit(DENIED_EVENT, operation, actorUserId, null, scopeId, scopeType, groupId,
                    permissionNames(oldPermissionIds), permissionNames(newPermissionIds));
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return true;
    }

    /**
     * 現在値又は置換後の割当に課金権限グループがあれば、ADMIN から同一 scope の
     * DEPUTY_ADMIN への操作だけを許可する。
     *
     * @return 課金権限を含む保護対象操作なら true
     */
    public boolean authorizeAssignment(
            Long actorUserId,
            Long recipientUserId,
            Long scopeId,
            String scopeType,
            Collection<Long> oldGroupIds,
            Collection<Long> newGroupIds) {
        List<Long> oldPermissionIds = permissionIdsOfGroups(oldGroupIds);
        List<Long> newPermissionIds = permissionIdsOfGroups(newGroupIds);
        List<Long> permissionIds = Stream.concat(oldPermissionIds.stream(), newPermissionIds.stream())
                .distinct()
                .toList();
        if (!containsBillingPermission(permissionIds)) {
            return false;
        }
        boolean allowed = !Objects.equals(actorUserId, recipientUserId)
                && accessControlService.isAdmin(actorUserId, scopeId, scopeType)
                && "DEPUTY_ADMIN".equals(
                        accessControlService.getRoleName(recipientUserId, scopeId, scopeType));
        if (!allowed) {
            audit(DENIED_EVENT, Operation.ASSIGN, actorUserId, recipientUserId,
                    scopeId, scopeType, null,
                    permissionNames(oldPermissionIds), permissionNames(newPermissionIds));
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return true;
    }

    private List<Long> permissionIdsOfGroups(Collection<Long> groupIds) {
        return safe(groupIds).stream()
                .filter(Objects::nonNull)
                .distinct()
                .flatMap(groupId -> groupPermissionRepository.findByGroupId(groupId).stream())
                .map(PermissionGroupPermissionEntity::getPermissionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    public void recordSuccess(
            Operation operation,
            Long actorUserId,
            Long targetUserId,
            Long scopeId,
            String scopeType,
            Long groupId,
            Collection<Long> oldPermissionIds,
            Collection<Long> newPermissionIds) {
        Runnable successAudit = () -> audit(
                "BILLING_PERMISSION_GROUP_" + operation.name(), operation,
                actorUserId, targetUserId, scopeId, scopeType, groupId,
                permissionNames(oldPermissionIds), permissionNames(newPermissionIds));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    successAudit.run();
                }
            });
        } else {
            successAudit.run();
        }
    }

    private boolean containsBillingPermission(List<Long> permissionIds) {
        if (permissionIds.isEmpty()) {
            return false;
        }
        return permissionRepository.findByIdIn(permissionIds).stream()
                .map(PermissionEntity::getName)
                .anyMatch(name -> TEAM_PERMISSION.equals(name)
                        || ORGANIZATION_PERMISSION.equals(name));
    }

    private List<String> permissionNames(Collection<Long> permissionIds) {
        if (safe(permissionIds).isEmpty()) {
            return List.of();
        }
        return permissionRepository.findByIdIn(safe(permissionIds).stream()
                        .filter(Objects::nonNull).distinct().toList()).stream()
                .map(PermissionEntity::getName)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private void audit(
            String eventType,
            Operation operation,
            Long actorUserId,
            Long targetUserId,
            Long scopeId,
            String scopeType,
            Long groupId,
            List<String> oldPermissions,
            List<String> newPermissions) {
        Long teamId = "TEAM".equals(scopeType) ? scopeId : null;
        Long organizationId = "ORGANIZATION".equals(scopeType) ? scopeId : null;
        String metadata = "{\"operation\":\"%s\",\"groupId\":%s,\"oldPermissions\":%s,\"newPermissions\":%s}"
                .formatted(operation.name(), groupId == null ? "null" : groupId.toString(),
                        jsonArray(oldPermissions), jsonArray(newPermissions));
        auditLogService.record(eventType, actorUserId, targetUserId,
                teamId, organizationId, null, null, null, metadata);
    }

    private String jsonArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static <T> Collection<T> safe(Collection<T> values) {
        return values == null ? List.of() : values;
    }
}
