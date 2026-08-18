package com.mannschaft.app.schedule.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import com.mannschaft.app.schedule.ScheduleTargetMode;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduleTargetServiceTest {
    private ScheduleTargetRepository targetRepository;
    private MemberQueryDispatcher memberQueryDispatcher;
    private ScopeMemberCalendarSettingRepository calendarSettingRepository;
    private ScheduleTargetService service;
    private ScheduleEntity schedule;

    @BeforeEach
    void setUp() {
        targetRepository = mock(ScheduleTargetRepository.class);
        memberQueryDispatcher = mock(MemberQueryDispatcher.class);
        calendarSettingRepository = mock(ScopeMemberCalendarSettingRepository.class);
        service = new ScheduleTargetService(targetRepository, memberQueryDispatcher, calendarSettingRepository);
        schedule = ScheduleEntity.builder().teamId(10L).targetMode(ScheduleTargetMode.ALL_MEMBERS).build();
    }

    @Test
    void allMembers_is_default_and_rejects_targets() {
        service.replaceForCreate(schedule, "TEAM", 10L, null, null);
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "ALL_MEMBERS", List.of(1L)));
    }

    @Test
    void selectedMembers_rejects_empty_duplicate_and_over500() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(1L, 1L)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS",
                        java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList()));
    }

    @Test
    void selectedMembers_accepts_one_and_500_active_members_in_a_single_membership_query() {
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(7L)));
        service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(7L));
        verify(targetRepository).saveAll(any());
        verify(memberQueryDispatcher).queryMembers(10L, ScopeType.TEAM, null);
    }

    @Test
    void selectedMembers_rejects_inactive_or_other_scope_member_without_writing() {
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(1L)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(1L, 2L)));
        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void update_without_target_fields_preserves_existing_assignment() {
        service.replaceForUpdate(schedule, "TEAM", 10L, null, null);
        verify(targetRepository, never()).deleteByScheduleId(any());
    }

    private MemberDto member(Long userId) {
        return new MemberDto(userId, "member", null, "MEMBER", null);
    }
}
