package com.mannschaft.app.event.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link EventScopeAccessGuard} ドメイン単体テスト（F03.8 イベント詳細 IDOR 根治）。
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>URL の scopeId と event.scope の scopeId が不一致なら EVENT_NOT_FOUND（404 秘匿）。</li>
 *   <li>URL の scopeType と event.scopeType が不一致なら EVENT_NOT_FOUND（404 秘匿）。</li>
 *   <li>帰属一致でも非メンバーなら COMMON_002（403）。</li>
 *   <li>帰属一致かつメンバーなら通過（検証済みイベントを返す）。</li>
 *   <li>SYSTEM_ADMIN は帰属一致なら常に通過。</li>
 *   <li>書き込み系（requireScopeAdmin）はメンバーでは 403、ADMIN/DEPUTY_ADMIN のみ通過。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventScopeAccessGuard ドメイン単体テスト（IDOR 根治）")
class EventScopeAccessGuardTest {

    @Mock
    private EventService eventService;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private EventScopeAccessGuard guard;

    private static final Long USER_ID = 42L;
    private static final Long TEAM_ID = 303L;
    private static final Long OTHER_TEAM_ID = 304L;
    private static final Long EVENT_ID = 169L;

    /** team 303 に帰属するイベント（実証再現: eventId=169 は team303 のもの）。 */
    private EventEntity teamEvent(Long scopeId) {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(scopeId)
                .slug("annual-meeting")
                .build();
    }

    @Nested
    @DisplayName("requireScopeMember（詳細取得）")
    class RequireScopeMember {

        @Test
        @DisplayName("別チームIDで他チームのイベントを引くと 404 EVENT_NOT_FOUND（IDOR 秘匿）")
        void mismatchedTeamId_throws404() {
            // URL は teams/304 だがイベントは team303 帰属 → 帰属不一致
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));

            assertThatThrownBy(() -> guard.requireScopeMember(
                    USER_ID, EventScopeType.TEAM, OTHER_TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("org 経由で team のイベントを引くと scopeType 不一致で 404")
        void mismatchedScopeType_throws404() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));

            // organizations/303/events/169 だがイベントは TEAM 帰属
            assertThatThrownBy(() -> guard.requireScopeMember(
                    USER_ID, EventScopeType.ORGANIZATION, TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("帰属一致でも非メンバーなら 403 COMMON_002")
        void belongsButNotMember_throws403() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> guard.requireScopeMember(
                    USER_ID, EventScopeType.TEAM, TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("帰属一致かつメンバーなら通過（検証済みイベントを返す）")
        void belongsAndMember_passes() {
            EventEntity event = teamEvent(TEAM_ID);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            EventEntity result = guard.requireScopeMember(USER_ID, EventScopeType.TEAM, TEAM_ID, EVENT_ID);

            assertThat(result).isSameAs(event);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は帰属一致なら常に通過")
        void systemAdmin_passes() {
            EventEntity event = teamEvent(TEAM_ID);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            EventEntity result = guard.requireScopeMember(USER_ID, EventScopeType.TEAM, TEAM_ID, EVENT_ID);

            assertThat(result).isSameAs(event);
        }

        @Test
        @DisplayName("未認証（userId=null）は 403")
        void nullUser_throws403() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));

            assertThatThrownBy(() -> guard.requireScopeMember(
                    null, EventScopeType.TEAM, TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    @Nested
    @DisplayName("requireScopeAdmin（書き込み系）")
    class RequireScopeAdmin {

        @Test
        @DisplayName("別チームIDなら 404 EVENT_NOT_FOUND（帰属先で秘匿）")
        void mismatchedTeamId_throws404() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));

            assertThatThrownBy(() -> guard.requireScopeAdmin(
                    USER_ID, EventScopeType.TEAM, OTHER_TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("帰属一致でも単なるメンバー（非 ADMIN）なら 403")
        void memberButNotAdmin_throws403() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> guard.requireScopeAdmin(
                    USER_ID, EventScopeType.TEAM, TEAM_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("帰属一致かつ ADMIN/DEPUTY_ADMIN なら通過")
        void adminOrAbove_passes() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            guard.requireScopeAdmin(USER_ID, EventScopeType.TEAM, TEAM_ID, EVENT_ID);
            // 例外を投げなければ成功
        }
    }

    /**
     * {@link EventScopeAccessGuard#requireMemberByEventId} / {@link EventScopeAccessGuard#requireAdminByEventId}
     * のテスト（Wave3-B12event: eventId のみを path に持つフラットなサブリソース Controller 向け）。
     */
    @Nested
    @DisplayName("requireMemberByEventId / requireAdminByEventId（フラットサブリソース向け）")
    class RequireByEventId {

        @Test
        @DisplayName("requireMemberByEventId: 非メンバーは403（イベント自身のスコープを信頼源とする）")
        void requireMemberByEventId_notMember_throws403() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> guard.requireMemberByEventId(USER_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("requireMemberByEventId: メンバーは通過（検証済みイベントを返す）")
        void requireMemberByEventId_member_passes() {
            EventEntity event = teamEvent(TEAM_ID);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            EventEntity result = guard.requireMemberByEventId(USER_ID, EVENT_ID);

            assertThat(result).isSameAs(event);
        }

        @Test
        @DisplayName("requireMemberByEventId: イベント不在は404 EVENT_NOT_FOUND")
        void requireMemberByEventId_eventNotFound_throws404() {
            given(eventService.findEventOrThrow(EVENT_ID))
                    .willThrow(new BusinessException(EventErrorCode.EVENT_NOT_FOUND));

            assertThatThrownBy(() -> guard.requireMemberByEventId(USER_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(EventErrorCode.EVENT_NOT_FOUND);
        }

        @Test
        @DisplayName("requireAdminByEventId: 非ADMINメンバーは403")
        void requireAdminByEventId_memberButNotAdmin_throws403() {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(teamEvent(TEAM_ID));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> guard.requireAdminByEventId(USER_ID, EVENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("requireAdminByEventId: ADMIN/DEPUTY_ADMINは通過")
        void requireAdminByEventId_admin_passes() {
            EventEntity event = teamEvent(TEAM_ID);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            EventEntity result = guard.requireAdminByEventId(USER_ID, EVENT_ID);

            assertThat(result).isSameAs(event);
        }

        @Test
        @DisplayName("requireAdminByEventId: SYSTEM_ADMINは常に通過")
        void requireAdminByEventId_systemAdmin_passes() {
            EventEntity event = teamEvent(TEAM_ID);
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(event);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            EventEntity result = guard.requireAdminByEventId(USER_ID, EVENT_ID);

            assertThat(result).isSameAs(event);
        }
    }
}
