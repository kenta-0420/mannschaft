package com.mannschaft.app.billing;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 契約変更トランザクションを権限解除と直列化し、commit対象の操作者権限を再確認する。 */
@Component
@RequiredArgsConstructor
public class BillingOperationAuthorizer {

    private static final String TEAM_PERMISSION = "MANAGE_TEAM_BILLING";
    private static final String ORGANIZATION_PERMISSION = "MANAGE_ORGANIZATION_BILLING";

    private final UserRowLockService userRowLockService;
    private final BillingAccessRepository billingAccessRepository;

    /**
     * 呼び出し元の書込トランザクション内で操作者行を先にロックし、現在権限を再確認する。
     * 権限解除側も対象ユーザー行を先にロックするため、解除commitとの順序が一意になる。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCanManage(
            Long operatorUserId,
            EntitlementScopeKind scopeKind,
            Long scopeId) {
        if (operatorUserId == null || scopeKind == null || scopeId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        userRowLockService.lock(operatorUserId);
        if (scopeKind != EntitlementScopeKind.USER) {
            billingAccessRepository.lockAssignedPermissionGroups(operatorUserId, scopeKind, scopeId);
        }

        boolean allowed = switch (scopeKind) {
            case USER -> operatorUserId.equals(scopeId);
            case TEAM -> billingAccessRepository.existsAdmin(operatorUserId, scopeKind, scopeId)
                    || billingAccessRepository.existsDeputyPermissionGroup(
                    operatorUserId, scopeKind, scopeId, TEAM_PERMISSION);
            case ORG -> billingAccessRepository.existsAdmin(operatorUserId, scopeKind, scopeId)
                    || billingAccessRepository.existsDeputyPermissionGroup(
                    operatorUserId, scopeKind, scopeId, ORGANIZATION_PERMISSION);
        };
        if (!allowed) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
