package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** インシデント本体とコメントで共有する可視性判定。 */
@Component
@RequiredArgsConstructor
public class IncidentAccessGuard {

    private static final String USER_ASSIGNEE_TYPE = "USER";

    private final AccessControlService accessControlService;
    private final IncidentAssignmentRepository assignmentRepository;

    /** ID 直指定の対象を閲覧できることを要求し、拒否時は存在を秘匿して 404 を返す。 */
    public boolean requireVisibleOrConceal(IncidentEntity incident, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (!accessControlService.isMember(userId, incident.getScopeId(), incident.getScopeType())) {
            throw concealed();
        }
        if (accessControlService.isAdminOrAbove(userId, incident.getScopeId(), incident.getScopeType())) {
            return true;
        }
        if (accessControlService.isSupporter(userId, incident.getScopeId(), incident.getScopeType())) {
            throw concealed();
        }
        boolean isReporter = userId.equals(incident.getReportedBy());
        boolean isUserAssignee = assignmentRepository.existsByIncidentIdAndUserIdAndAssigneeType(
                incident.getId(), userId, USER_ASSIGNEE_TYPE);
        if (!isReporter && !isUserAssignee) {
            throw concealed();
        }
        return false;
    }

    /** scope 指定一覧の可視性モードを返す。非所属・SUPPORTER は 403 とする。 */
    public boolean requireListVisibility(Long userId, Long scopeId, String scopeType) {
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        accessControlService.checkMembership(userId, scopeId, scopeType);
        if (accessControlService.isSupporter(userId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return accessControlService.isAdminOrAbove(userId, scopeId, scopeType);
    }

    private BusinessException concealed() {
        return new BusinessException(IncidentErrorCode.INCIDENT_002);
    }
}
