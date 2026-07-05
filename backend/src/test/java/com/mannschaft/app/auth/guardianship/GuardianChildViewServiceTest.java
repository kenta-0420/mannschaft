package com.mannschaft.app.auth.guardianship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mannschaft.app.auth.dto.GuardianChildAnnouncementsResponse;
import com.mannschaft.app.auth.dto.GuardianChildMembershipsResponse;
import com.mannschaft.app.auth.dto.GuardianChildProxyActionsResponse;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.BulletinThreadService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.dto.ProxyActionView;
import com.mannschaft.app.proxy.service.ProxyInputQueryService;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * {@link GuardianChildViewService} の単体テスト（F08.9 件2 保護者による子データ閲覧専用見守り）。
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-1: ALLOWED の親が schedules → 子の可視予定を返す（+ 4 面すべて委譲）</li>
 *   <li>AC-2: LINK_NOT_FOUND → 403 GUARDIANSHIP_LINK_NOT_FOUND</li>
 *   <li>AC-3: 存在しない/不整合 child は evaluateSwitch=LINK_NOT_FOUND → 403（情報を漏らさない）</li>
 *   <li>AC-4: AGE_LOCKED（12歳以上）→ 403 GUARDIANSHIP_SWITCH_AGE_LOCKED / ALLOWED → 200</li>
 *   <li>AC-5: getMyCalendar に childUserId が渡る（子基準 F00）</li>
 *   <li>AC-6: proxy-actions は findBySubjectUserIdOrderByCreatedAtDesc(childUserId) のみ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianChildViewService テスト（F08.9 件2 子データ閲覧見守り）")
class GuardianChildViewServiceTest {

    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 11L;
    private static final LocalDateTime FROM = LocalDateTime.parse("2026-07-01T00:00:00");
    private static final LocalDateTime TO = LocalDateTime.parse("2026-07-31T23:59:59");

    @Mock
    private GuardianshipSwitchService guardianshipSwitchService;
    @Mock
    private ScheduleQueryService scheduleQueryService;
    @Mock
    private ScheduleAttendanceService scheduleAttendanceService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private BulletinThreadService bulletinThreadService;
    @Mock
    private ProxyInputQueryService proxyInputQueryService;

    @InjectMocks
    private GuardianChildViewService service;

    // ========================================
    // AC-1 / AC-5: schedules 正常系（子基準）
    // ========================================

    @Test
    @DisplayName("AC-1/AC-5: ALLOWED の親が schedules → getMyCalendar(childUserId,...) に委譲（子基準）")
    void schedules_allowed_delegatesWithChildId() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.ALLOWED);
        CalendarEntryResponse entry = CalendarEntryResponse.builder().id(500L).build();
        given(scheduleQueryService.getMyCalendar(CHILD_ID, FROM, TO)).willReturn(List.of(entry));

        List<CalendarEntryResponse> result =
                service.getChildSchedules(GUARDIAN_ID, CHILD_ID, FROM, TO);

        assertThat(result).hasSize(1);
        // AC-5: viewer=子（childUserId）で引かれる。
        verify(scheduleQueryService).getMyCalendar(eq(CHILD_ID), eq(FROM), eq(TO));
    }

    // ========================================
    // AC-2: リンクなし → 403 LINK_NOT_FOUND
    // ========================================

    @Test
    @DisplayName("AC-2: LINK_NOT_FOUND → 403 GUARDIANSHIP_LINK_NOT_FOUND（委譲せず）")
    void schedules_linkNotFound_403() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.LINK_NOT_FOUND);

        assertThatThrownBy(() -> service.getChildSchedules(GUARDIAN_ID, CHILD_ID, FROM, TO))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);

        verify(scheduleQueryService, never()).getMyCalendar(any(), any(), any());
    }

    // ========================================
    // AC-3: 存在しない/不整合 child も同じ 403（evaluateSwitch が一本化）
    // ========================================

    @Test
    @DisplayName("AC-3: 存在しない/不整合 child は LINK_NOT_FOUND に一本化 → 403（情報を漏らさない）")
    void memberships_nonexistentChild_403() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.LINK_NOT_FOUND);

        assertThatThrownBy(() -> service.getChildMemberships(GUARDIAN_ID, CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
    }

    // ========================================
    // AC-4: 年齢封印
    // ========================================

    @Test
    @DisplayName("AC-4: AGE_LOCKED（12歳以上）→ 403 GUARDIANSHIP_SWITCH_AGE_LOCKED")
    void attendance_ageLocked_403() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.AGE_LOCKED);

        assertThatThrownBy(() -> service.getChildAttendanceStats(GUARDIAN_ID, CHILD_ID, FROM, TO))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED);

        verify(scheduleAttendanceService, never()).getMyAttendanceStats(any(), any(), any());
    }

    @Test
    @DisplayName("AC-4: ALLOWED（12歳未満）で attendance → getMyAttendanceStats(childUserId,...) に委譲")
    void attendance_allowed_delegates() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.ALLOWED);
        AttendanceStatsResponse stats = new AttendanceStatsResponse(CHILD_ID, 10, 8, 1, 1, 80.0);
        given(scheduleAttendanceService.getMyAttendanceStats(CHILD_ID, FROM, TO)).willReturn(stats);

        AttendanceStatsResponse result =
                service.getChildAttendanceStats(GUARDIAN_ID, CHILD_ID, FROM, TO);

        assertThat(result.getUserId()).isEqualTo(CHILD_ID);
        verify(scheduleAttendanceService).getMyAttendanceStats(eq(CHILD_ID), eq(FROM), eq(TO));
    }

    // ========================================
    // ③ 所属（名称合成）
    // ========================================

    @Test
    @DisplayName("③ memberships: 所属チーム/組織を ID→名称合成で返す")
    void memberships_ok() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.ALLOWED);
        given(membershipService.getActiveTeamIdsByUser(CHILD_ID)).willReturn(List.of(200L));
        given(membershipService.getActiveOrgIdsByUser(CHILD_ID)).willReturn(List.of(300L));
        given(nameResolverService.resolveScopeName("TEAM", 200L)).willReturn("サッカークラブ");
        given(nameResolverService.resolveScopeName("ORGANIZATION", 300L)).willReturn("県協会");

        GuardianChildMembershipsResponse result = service.getChildMemberships(GUARDIAN_ID, CHILD_ID);

        assertThat(result.teams()).containsExactly(
                new GuardianChildMembershipsResponse.ScopeRef(200L, "サッカークラブ"));
        assertThat(result.organizations()).containsExactly(
                new GuardianChildMembershipsResponse.ScopeRef(300L, "県協会"));
    }

    // ========================================
    // ④ お知らせ（全所属スコープ横断・更新日時降順）
    // ========================================

    @Test
    @DisplayName("④ announcements: 所属スコープのスレッドを合算し更新日時降順で返す（子基準 listThreads）")
    void announcements_mergedSortedByUpdatedAt() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.ALLOWED);
        given(membershipService.getActiveTeamIdsByUser(CHILD_ID)).willReturn(List.of(200L));
        given(membershipService.getActiveOrgIdsByUser(CHILD_ID)).willReturn(List.of(300L));
        given(nameResolverService.resolveScopeName("TEAM", 200L)).willReturn("サッカークラブ");
        given(nameResolverService.resolveScopeName("ORGANIZATION", 300L)).willReturn("県協会");

        ThreadResponse teamThread = ThreadResponse.builder()
                .id(1L).scopeType("TEAM").scopeId(200L).title("練習中止").priority("IMPORTANT")
                .updatedAt(LocalDateTime.parse("2026-07-02T10:00:00")).build();
        ThreadResponse orgThread = ThreadResponse.builder()
                .id(2L).scopeType("ORGANIZATION").scopeId(300L).title("大会案内").priority("INFO")
                .updatedAt(LocalDateTime.parse("2026-07-03T10:00:00")).build();

        given(bulletinThreadService.listThreads(eq(ScopeType.TEAM), eq(200L), eq(CHILD_ID), any(Pageable.class)))
                .willReturn(page(List.of(teamThread)));
        given(bulletinThreadService.listThreads(eq(ScopeType.ORGANIZATION), eq(300L), eq(CHILD_ID), any(Pageable.class)))
                .willReturn(page(List.of(orgThread)));

        GuardianChildAnnouncementsResponse result =
                service.getChildAnnouncements(GUARDIAN_ID, CHILD_ID, 0, 20);

        assertThat(result.totalElements()).isEqualTo(2L);
        // 更新日時降順: org(07-03) が先、team(07-02) が後。
        assertThat(result.items()).extracting(GuardianChildAnnouncementsResponse.AnnouncementItem::threadId)
                .containsExactly(2L, 1L);
        assertThat(result.items().get(0).scopeName()).isEqualTo("県協会");
        assertThat(result.items().get(1).scopeName()).isEqualTo("サッカークラブ");
    }

    // ========================================
    // AC-6: proxy-actions（subject=子 のみ）
    // ========================================

    @Test
    @DisplayName("AC-6: proxy-actions は subject=子 のレコードのみ（ProxyInputQueryService.getActionsBySubject 経由）")
    void proxyActions_subjectChildOnly() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.ALLOWED);
        ProxyActionView view = new ProxyActionView(
                77L, CHILD_ID, GUARDIAN_ID, "SCHEDULE_ATTENDANCE", "SCHEDULE_ATTENDANCE", 999L,
                "GUARDIANSHIP_SWITCH", LocalDateTime.parse("2026-07-04T09:00:00"));
        given(proxyInputQueryService.getActionsBySubject(CHILD_ID)).willReturn(List.of(view));

        GuardianChildProxyActionsResponse result = service.getChildProxyActions(GUARDIAN_ID, CHILD_ID);

        // 委譲は proxy ドメインの query service 経由（Entity/Repository 直参照なし＝ドメイン境界）。
        verify(proxyInputQueryService).getActionsBySubject(CHILD_ID);
        assertThat(result.items()).hasSize(1);
        GuardianChildProxyActionsResponse.ProxyActionItem item = result.items().get(0);
        assertThat(item.id()).isEqualTo(77L);
        assertThat(item.proxyUserId()).isEqualTo(GUARDIAN_ID);
        assertThat(item.featureScope()).isEqualTo("SCHEDULE_ATTENDANCE");
        assertThat(item.inputSource()).isEqualTo("GUARDIANSHIP_SWITCH");
    }

    @Test
    @DisplayName("AC-2（proxy）: LINK_NOT_FOUND → 403（proxy-actions も委譲せず）")
    void proxyActions_linkNotFound_403() {
        given(guardianshipSwitchService.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                .willReturn(SwitchVerdict.LINK_NOT_FOUND);

        assertThatThrownBy(() -> service.getChildProxyActions(GUARDIAN_ID, CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);

        verify(proxyInputQueryService, never()).getActionsBySubject(any());
    }

    private static Page<ThreadResponse> page(List<ThreadResponse> content) {
        return new PageImpl<>(content, PageRequest.of(0, 20), content.size());
    }
}
