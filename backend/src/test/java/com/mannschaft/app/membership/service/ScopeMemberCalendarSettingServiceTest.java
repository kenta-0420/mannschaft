package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeMemberCalendarSettingServiceTest {
    @Test
    void fallback_is_deterministic_and_scope_specific() {
        String first = ScopeMemberCalendarSettingService.fallback(ScopeType.TEAM, 1L, 2L);
        assertThat(ScopeMemberCalendarSettingService.fallback(ScopeType.TEAM, 1L, 2L)).isEqualTo(first);
        assertThat(first).matches("^#[0-9A-F]{6}$");
    }

    @Test
    void override_rejects_user_outside_scope() {
        var repository = mock(ScopeMemberCalendarSettingRepository.class);
        var dispatcher = mock(MemberQueryDispatcher.class);
        when(dispatcher.queryMembers(1L, ScopeType.TEAM, null)).thenReturn(List.<MemberDto>of());
        var service = new ScopeMemberCalendarSettingService(repository, dispatcher);
        assertThatThrownBy(() -> service.override(ScopeType.TEAM, 1L, 9L, "#2563EB"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(MembershipErrorCode.MEMBERSHIP_024);
    }

    @Test
    void override_rejects_arbitrary_hex_outside_accessible_palette() {
        var repository = mock(ScopeMemberCalendarSettingRepository.class);
        var dispatcher = mock(MemberQueryDispatcher.class);
        var service = new ScopeMemberCalendarSettingService(repository, dispatcher);

        assertThatThrownBy(() -> service.override(ScopeType.TEAM, 1L, 9L, "#123456"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(MembershipErrorCode.MEMBERSHIP_023);
    }

    @Test
    void override_normalizes_lowercase_palette_color() {
        var repository = mock(ScopeMemberCalendarSettingRepository.class);
        var dispatcher = mock(MemberQueryDispatcher.class);
        when(dispatcher.queryMembers(1L, ScopeType.TEAM, null))
                .thenReturn(List.of(new MemberDto(9L, "member", null, "MEMBER", null)));
        var service = new ScopeMemberCalendarSettingService(repository, dispatcher);

        var response = service.override(ScopeType.TEAM, 1L, 9L, "#2563eb");

        assertThat(response.calendarColor()).isEqualTo("#2563EB");
    }
}
