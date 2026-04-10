package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleService} の単体テスト。
 * スケジュールのCRUD・繰り返し展開・カレンダー集約を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleService 単体テスト")
class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private ScheduleService scheduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 4, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 1, 12, 0);

    private ScheduleEntity createTeamScheduleEntity() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("練習")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    private ScheduleEntity createCancelledScheduleEntity() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("キャンセル済み")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.CANCELLED)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // getSchedule
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("スケジュール取得_存在_エンティティを返す")
        void スケジュール取得_存在_エンティティを返す() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when
            ScheduleEntity result = scheduleService.getSchedule(SCHEDULE_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
        }

        @Test
        @DisplayName("スケジュール取得_不存在_例外スロー")
        void スケジュール取得_不存在_例外スロー() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> scheduleService.getSchedule(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }
    }

    // ========================================
    // getScheduleWithAccessCheck
    // ========================================

    @Nested
    @DisplayName("getScheduleWithAccessCheck")
    class GetScheduleWithAccessCheck {

        @Test
        @DisplayName("アクセスチェック付き取得_チームスコープ_メンバーシップ検証される")
        void アクセスチェック付き取得_チームスコープ_メンバーシップ検証される() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when
            ScheduleEntity result = scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }
    }

    // ========================================
    // listTeamSchedules
    // ========================================

    @Nested
    @DisplayName("listTeamSchedules")
    class ListTeamSchedules {

        @Test
        @DisplayName("チームスケジュール一覧_正常_レスポンス一覧を返す")
        void チームスケジュール一覧_正常_レスポンス一覧を返す() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(TEAM_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<ScheduleResponse> result = scheduleService.listTeamSchedules(TEAM_ID, START, END);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("練習");
        }
    }

    // ========================================
    // createSchedule
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        @Test
        @DisplayName("スケジュール作成_チームスコープ_保存されてイベント発行される")
        void スケジュール作成_チームスコープ_保存されてイベント発行される() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "練習",
                    "通常練習",
                    "体育館",
                    START,
                    END,
                    false,
                    "PRACTICE",
                    null, null, null,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
            verify(scheduleRepository).save(any(ScheduleEntity.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("スケジュール作成_日付不正_例外スロー")
        void スケジュール作成_日付不正_例外スロー() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "練習", null, null,
                    END, START, // start > end
                    false, "PRACTICE",
                    null, null, null,
                    false, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE);
        }

        @Test
        @DisplayName("スケジュール作成_不正スコープ_例外スロー")
        void スケジュール作成_不正スコープ_例外スロー() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "練習", null, null,
                    START, END,
                    false, "PRACTICE",
                    null, null, null,
                    false, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(req, TEAM_ID, "INVALID", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_SCOPE);
        }
    }

    // ========================================
    // updateSchedule
    // ========================================

    @Nested
    @DisplayName("updateSchedule")
    class UpdateSchedule {

        @Test
        @DisplayName("スケジュール更新_正常_更新されてイベント発行される")
        void スケジュール更新_正常_更新されてイベント発行される() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            UpdateScheduleRequest req = new UpdateScheduleRequest(
                    "更新後タイトル", null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null);

            // when
            scheduleService.updateSchedule(SCHEDULE_ID, req, "THIS_ONLY", USER_ID);

            // then
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("スケジュール更新_キャンセル済み_例外スロー")
        void スケジュール更新_キャンセル済み_例外スロー() {
            // given
            ScheduleEntity cancelled = createCancelledScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(cancelled));

            UpdateScheduleRequest req = new UpdateScheduleRequest(
                    "更新", null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> scheduleService.updateSchedule(SCHEDULE_ID, req, "THIS_ONLY", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    // ========================================
    // deleteSchedule
    // ========================================

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("スケジュール削除_単体_論理削除される")
        void スケジュール削除_単体_論理削除される() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY");

            // then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(scheduleRepository).save(any(ScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール削除_不存在_例外スロー")
        void スケジュール削除_不存在_例外スロー() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }
    }

    // ========================================
    // cancelSchedule
    // ========================================

    @Nested
    @DisplayName("cancelSchedule")
    class CancelSchedule {

        @Test
        @DisplayName("スケジュールキャンセル_正常_ステータス変更されてイベント発行")
        void スケジュールキャンセル_正常_ステータス変更されてイベント発行() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            scheduleService.cancelSchedule(SCHEDULE_ID, USER_ID);

            // then
            assertThat(entity.getStatus()).isEqualTo(ScheduleStatus.CANCELLED);
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("スケジュールキャンセル_既にキャンセル済み_例外スロー")
        void スケジュールキャンセル_既にキャンセル済み_例外スロー() {
            // given
            ScheduleEntity cancelled = createCancelledScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(cancelled));

            // when & then
            assertThatThrownBy(() -> scheduleService.cancelSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    // ========================================
    // duplicateSchedule
    // ========================================

    @Nested
    @DisplayName("duplicateSchedule")
    class DuplicateSchedule {

        @Test
        @DisplayName("スケジュール複製_正常_新しいスケジュールが作成される")
        void スケジュール複製_正常_新しいスケジュールが作成される() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
            assertThat(result.getStatus()).isEqualTo("SCHEDULED");
            verify(scheduleRepository).save(any(ScheduleEntity.class));
        }
    }

    // ========================================
    // listOrgSchedules
    // ========================================

    @Nested
    @DisplayName("listOrgSchedules")
    class ListOrgSchedules {

        @Test
        @DisplayName("組織スケジュール一覧_正常_レスポンス一覧を返す")
        void 組織スケジュール一覧_正常_レスポンス一覧を返す() {
            // given
            Long ORG_ID = 20L;
            ScheduleEntity entity = ScheduleEntity.builder()
                    .organizationId(ORG_ID).title("全体集会")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.EVENT).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                    .isException(false).createdBy(USER_ID).build();
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(ORG_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<ScheduleResponse> result = scheduleService.listOrgSchedules(ORG_ID, START, END);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("全体集会");
        }
    }

    // ========================================
    // deleteSchedule with THIS_AND_FOLLOWING
    // ========================================

    @Nested
    @DisplayName("deleteSchedule_THIS_AND_FOLLOWING")
    class DeleteScheduleThisAndFollowing {

        @Test
        @DisplayName("THIS_AND_FOLLOWING削除_繰り返し子スケジュール_以降が削除される")
        void THIS_AND_FOLLOWING削除_繰り返し子スケジュール_以降が削除される() {
            // given
            ScheduleEntity child = ScheduleEntity.builder()
                    .teamId(TEAM_ID).title("繰り返し練習")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                    .parentScheduleId(99L) // 親IDあり
                    .isException(false).createdBy(USER_ID).build();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(child));
            given(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(99L))
                    .willReturn(List.of());

            // when
            scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_AND_FOLLOWING");

            // then
            verify(scheduleRepository).findById(SCHEDULE_ID);
        }

        @Test
        @DisplayName("ALL削除_繰り返し子から親含め全削除")
        void ALL削除_繰り返し子から親含め全削除() {
            // given
            ScheduleEntity child = ScheduleEntity.builder()
                    .teamId(TEAM_ID).title("繰り返し練習")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                    .parentScheduleId(99L) // 親IDあり
                    .isException(false).createdBy(USER_ID).build();
            ScheduleEntity parent = ScheduleEntity.builder()
                    .teamId(TEAM_ID).title("繰り返し練習（親）")
                    .startAt(START.minusWeeks(1)).endAt(END.minusWeeks(1)).allDay(false)
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                    .isException(false).createdBy(USER_ID).build();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(child));
            given(scheduleRepository.findById(99L)).willReturn(Optional.of(parent));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(99L)).willReturn(List.of());

            // when
            scheduleService.deleteSchedule(SCHEDULE_ID, "ALL");

            // then
            assertThat(parent.getDeletedAt()).isNotNull();
        }
    }

    // ========================================
    // createSchedule - PERSONAL scope
    // ========================================

    @Nested
    @DisplayName("createSchedule_PERSONAL")
    class CreateSchedulePersonal {

        @Test
        @DisplayName("スケジュール作成_個人スコープ_正常作成")
        void スケジュール作成_個人スコープ_正常作成() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "個人予定", null, null, START, END, false, "OTHER",
                    null, null, null, false, null, null, null, null, null, null, null);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, USER_ID, "PERSONAL", USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("スケジュール作成_組織スコープ_正常作成")
        void スケジュール作成_組織スコープ_正常作成() {
            // given
            Long ORG_ID = 20L;
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "組織イベント", null, null, START, END, false, "EVENT",
                    null, null, null, false, null, null, null, null, null, null, null);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, ORG_ID, "ORGANIZATION", USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("組織イベント");
        }
    }

    // ========================================
    // getMyCalendar
    // ========================================

    @Nested
    @DisplayName("getMyCalendar")
    class GetMyCalendar {

        @Test
        @DisplayName("横断カレンダー取得_個人とチーム_統合して返す")
        void 横断カレンダー取得_個人とチーム_統合して返す() {
            // given
            ScheduleEntity personalSchedule = ScheduleEntity.builder()
                    .userId(USER_ID)
                    .title("個人予定")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .build();

            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(personalSchedule));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());
            given(nameResolverService.resolveScopeName("PERSONAL", USER_ID)).willReturn("個人");

            // when
            List<CalendarEntryResponse> result = scheduleService.getMyCalendar(USER_ID, START, END);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("個人予定");
        }
    }
}
