package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;
import com.mannschaft.app.schedule.ScheduleTargetMode;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.entity.ScheduleTargetEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private ScopeMemberCalendarSettingService calendarSettingService;
    private ScheduleTargetService service;
    private ScheduleEntity schedule;

    @BeforeEach
    void setUp() {
        targetRepository = mock(ScheduleTargetRepository.class);
        memberQueryDispatcher = mock(MemberQueryDispatcher.class);
        calendarSettingService = mock(ScopeMemberCalendarSettingService.class);
        service = new ScheduleTargetService(targetRepository, memberQueryDispatcher, calendarSettingService);
        schedule = ScheduleEntity.builder().teamId(10L).targetMode(ScheduleTargetMode.ALL_MEMBERS).build();
    }

    @Test
    void allMembers_is_default_and_rejects_targets() {
        service.replaceForCreate(schedule, "TEAM", 10L, null, null);
        assertThatThrownBy(() -> service.replaceForCreate(
                schedule, "TEAM", 10L, "ALL_MEMBERS", List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ScheduleErrorCode.INVALID_TARGET_SELECTION);
    }

    @Test
    void selectedMembers_rejects_empty_duplicate_and_over500() {
        assertThatThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of()));
        assertThatThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(1L, 1L)));
        assertThatThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS",
                        java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList()));
    }

    @Test
    void selectedMembers_accepts_one_active_member_in_a_single_membership_query() {
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(7L)));
        service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(7L));
        verify(targetRepository).saveAll(any());
        verify(memberQueryDispatcher).queryMembers(10L, ScopeType.TEAM, null);
    }

    @Test
    void selectedMembers_accepts_exactly_500_active_members() {
        List<Long> userIds = java.util.stream.LongStream.rangeClosed(1, 500).boxed().toList();
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(userIds.stream().map(this::member).toList());

        service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", userIds);

        verify(targetRepository).saveAll(any());
        verify(memberQueryDispatcher).queryMembers(10L, ScopeType.TEAM, null);
    }

    @Test
    void selectedMembers_rejects_member_below_schedule_view_role() {
        schedule = ScheduleEntity.builder()
                .teamId(10L)
                .targetMode(ScheduleTargetMode.ALL_MEMBERS)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .build();
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(7L)));

        assertThatThrownBy(() ->
                service.replaceForCreate(schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(7L)));
        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void selectedMembers_rejects_inactive_or_other_scope_member_without_writing() {
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(1L)));
        assertThatThrownBy(() -> service.replaceForCreate(
                schedule, "TEAM", 10L, "SELECTED_MEMBERS", List.of(1L, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ScheduleErrorCode.SCHEDULE_TARGET_MEMBER_NOT_FOUND);
        verify(targetRepository, never()).saveAll(any());
    }

    @Test
    void update_without_target_fields_preserves_existing_assignment() {
        service.replaceForUpdate(schedule, "TEAM", 10L, null, null);
        verify(targetRepository, never()).deleteByScheduleId(any());
    }

    @Test
    void responses_omit_departed_target_but_keep_stored_target_count_and_selected_mode() {
        ReflectionTestUtils.setField(schedule, "id", 50L);
        when(targetRepository.findByScheduleIdInOrderByScheduleIdAscUserIdAsc(List.of(50L)))
                .thenReturn(List.of(
                        ScheduleTargetEntity.builder().scheduleId(50L).userId(1L).build(),
                        ScheduleTargetEntity.builder().scheduleId(50L).userId(2L).build()));
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null))
                .thenReturn(List.of(member(1L)));
        when(calendarSettingService.resolveColors(
                eq(ScopeType.TEAM), eq(10L), anyCollection())).thenReturn(java.util.Map.of());
        schedule.updateTargetMode(ScheduleTargetMode.SELECTED_MEMBERS);

        var response = service.responsesForSchedules(List.of(schedule), true).get(50L);

        assertThat(response.targetMode()).isEqualTo("SELECTED_MEMBERS");
        assertThat(response.targetCount()).isEqualTo(2);
        assertThat(response.targets()).extracting(target -> target.userId()).containsExactly(1L);
    }

    @Test
    void responses_keep_selected_mode_when_anonymization_removes_every_target() {
        ReflectionTestUtils.setField(schedule, "id", 51L);
        schedule.updateTargetMode(ScheduleTargetMode.SELECTED_MEMBERS);
        when(targetRepository.findByScheduleIdInOrderByScheduleIdAscUserIdAsc(List.of(51L)))
                .thenReturn(List.of());
        when(memberQueryDispatcher.queryMembers(10L, ScopeType.TEAM, null)).thenReturn(List.of());

        var response = service.responsesForSchedules(List.of(schedule), true).get(51L);

        assertThat(response.targetMode()).isEqualTo("SELECTED_MEMBERS");
        assertThat(response.targetCount()).isZero();
        assertThat(response.targets()).isEmpty();
    }

    private MemberDto member(Long userId) {
        return new MemberDto(userId, "member", null, "MEMBER", null);
    }
}
