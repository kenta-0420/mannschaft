package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.repository.IncidentAssignmentRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

class IncidentAccessGuardTest {
    @Test
    void systemAdminCanViewWithoutMembership() {
        AccessControlService access = mock(AccessControlService.class);
        IncidentAccessGuard guard = new IncidentAccessGuard(access, mock(IncidentAssignmentRepository.class));
        given(access.isSystemAdmin(1L)).willReturn(true);
        assertThat(guard.requireVisibleOrConceal(incident(), 1L)).isTrue();
    }

    @Test
    void unrelatedMemberIsConcealed() {
        AccessControlService access = mock(AccessControlService.class);
        IncidentAccessGuard guard = new IncidentAccessGuard(access, mock(IncidentAssignmentRepository.class));
        given(access.isMember(2L, 10L, "TEAM")).willReturn(true);
        assertThatThrownBy(() -> guard.requireVisibleOrConceal(incident(), 2L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(IncidentErrorCode.INCIDENT_002);
    }

    @Test
    void reporterAndUserAssigneeCanViewButSupporterReporterIsConcealed() {
        AccessControlService access = mock(AccessControlService.class);
        IncidentAssignmentRepository assignments = mock(IncidentAssignmentRepository.class);
        IncidentAccessGuard guard = new IncidentAccessGuard(access, assignments);
        given(access.isMember(3L, 10L, "TEAM")).willReturn(true);
        assertThat(guard.requireVisibleOrConceal(incident(), 3L)).isFalse();
        given(access.isMember(4L, 10L, "TEAM")).willReturn(true);
        given(assignments.existsByIncidentIdAndUserIdAndAssigneeType(100L, 4L, "USER")).willReturn(true);
        assertThat(guard.requireVisibleOrConceal(incident(), 4L)).isFalse();
        given(access.isSupporter(3L, 10L, "TEAM")).willReturn(true);
        assertThatThrownBy(() -> guard.requireVisibleOrConceal(incident(), 3L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void listAllowsSystemAdminAndScopeAdminButRejectsSupporter() {
        AccessControlService access = mock(AccessControlService.class);
        IncidentAccessGuard guard = new IncidentAccessGuard(access, mock(IncidentAssignmentRepository.class));
        given(access.isSystemAdmin(1L)).willReturn(true);
        assertThat(guard.requireListVisibility(1L, 10L, "TEAM")).isTrue();
        given(access.isAdminOrAbove(2L, 10L, "TEAM")).willReturn(true);
        assertThat(guard.requireListVisibility(2L, 10L, "TEAM")).isTrue();
        given(access.isSupporter(3L, 10L, "TEAM")).willReturn(true);
        assertThatThrownBy(() -> guard.requireListVisibility(3L, 10L, "TEAM")).isInstanceOf(BusinessException.class);
        verify(access).checkMembership(3L, 10L, "TEAM");
    }

    private IncidentEntity incident() {
        return IncidentEntity.builder().id(100L).scopeType("TEAM").scopeId(10L).reportedBy(3L).build();
    }
}
