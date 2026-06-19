package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleEventCategoryService;
import com.mannschaft.app.schedule.service.ScheduleQueryService;
import com.mannschaft.app.schedule.service.ScheduleRecurrenceService;
import com.mannschaft.app.schedule.service.ScheduleReminderService;
import com.mannschaft.app.schedule.service.ScheduleScheduledTaskService;
import com.mannschaft.app.schedule.service.ScheduleService;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private ScheduleEventCategoryService eventCategoryService;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @Mock
    private ScheduleQueryService queryService;

    @Mock
    private ScheduleRecurrenceService recurrenceService;

    @Mock
    private ScheduleScheduledTaskService scheduledTaskService;

    @Mock
    private ScheduleReminderService reminderService;

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @Mock
    private com.mannschaft.app.organization.service.OrganizationMembershipService organizationMembershipService;

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
    /** JST(+09:00) のオフセットを付与した OffsetDateTime（テスト用）。 */
    private static final OffsetDateTime START_ODT = OffsetDateTime.of(2026, 4, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime END_ODT = OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9));

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
        @DisplayName("アクセスチェック付き取得_F00 canView がtrueならそのまま許可")
        void アクセスチェック付き取得_canViewがtrueなら許可() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(contentVisibilityChecker.canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID))
                    .willReturn(true);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when
            ScheduleEntity result = scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
            verify(contentVisibilityChecker).canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);
            // canView=true なら配信母集団判定（越境窓口）は呼ばない
            org.mockito.Mockito.verifyNoInteractions(organizationMembershipService);
        }

        @Test
        @DisplayName("番人4: 関所(2)閲覧_組織スケジュールで canView=false でも配信母集団なら200で開ける")
        void 番人4_組織配下メンバーは出欠詳細を開ける() {
            // given: 組織スケジュール。canView=false（直接所属でない配下メンバー）だが、
            // includeSupporters トグル準拠の配信母集団に属する → OR寄せで閲覧許可。
            ScheduleEntity orgSchedule = ScheduleEntity.builder()
                    .organizationId(20L)
                    .title("組織練習")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .includeSupporters(false)
                    .commentOption(CommentOption.OPTIONAL)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();
            given(contentVisibilityChecker.canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID))
                    .willReturn(false);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(orgSchedule));
            given(organizationMembershipService.isInOrgDistributionAudience(20L, USER_ID, false))
                    .willReturn(true);

            // when
            ScheduleEntity result = scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID);

            // then: 200 相当でエンティティが返り、assertCanView（deny例外）には到達しない
            assertThat(result.getTitle()).isEqualTo("組織練習");
            verify(contentVisibilityChecker, org.mockito.Mockito.never())
                    .assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("番人5: 組織にも配下にも無関係なユーザーは assertCanView へ委譲され例外（403相当）")
        void 番人5_無関係ユーザーは例外() {
            // given: 組織スケジュール。canView=false かつ配信母集団にも非該当 → assertCanView 委譲で deny。
            ScheduleEntity orgSchedule = ScheduleEntity.builder()
                    .organizationId(20L)
                    .title("組織練習")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .includeSupporters(false)
                    .commentOption(CommentOption.OPTIONAL)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();
            given(contentVisibilityChecker.canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID))
                    .willReturn(false);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(orgSchedule));
            given(organizationMembershipService.isInOrgDistributionAudience(20L, USER_ID, false))
                    .willReturn(false);
            org.mockito.Mockito.doThrow(new BusinessException(
                            com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                    .when(contentVisibilityChecker)
                    .assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チームスケジュール_canView=false は配下概念なし→assertCanViewへ委譲で例外")
        void チームスケジュール_canView偽は委譲で例外() {
            // given: TEAM スケジュール（orgId null）。配信母集団判定は ORG のみのため呼ばれず assertCanView へ。
            ScheduleEntity entity = createTeamScheduleEntity();
            given(contentVisibilityChecker.canView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID))
                    .willReturn(false);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            org.mockito.Mockito.doThrow(new BusinessException(
                            com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                    .when(contentVisibilityChecker)
                    .assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);

            assertThatThrownBy(() -> scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
            // TEAM では配下判定（organization 越境窓口）を一切呼ばない
            org.mockito.Mockito.verifyNoInteractions(organizationMembershipService);
        }
    }

    // ========================================
    // listTeamSchedules
    // ========================================

    @Nested
    @DisplayName("listTeamSchedules")
    class ListTeamSchedules {

        @Test
        @DisplayName("チームスケジュール一覧_正常_QueryServiceに委譲する")
        void チームスケジュール一覧_正常_QueryServiceに委譲する() {
            // given
            ScheduleResponse stub = ScheduleResponse.builder()
                    .id(SCHEDULE_ID)
                    .content(new ScheduleResponse.ScheduleContentDto("練習", "SCHEDULED", "PRACTICE", null, true))
                    .time(new ScheduleResponse.ScheduleTimeDto(START, END, false))
                    .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                    .academic(new ScheduleResponse.ScheduleAcademicDto(null, null, null))
                    .audit(new ScheduleResponse.ScheduleAuditDto(null, null))
                    .build();
            given(queryService.listTeamSchedules(TEAM_ID, START, END, USER_ID))
                    .willReturn(List.of(stub));

            // when
            List<ScheduleResponse> result = scheduleService.listTeamSchedules(TEAM_ID, START, END, USER_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent().title()).isEqualTo("練習");
            verify(queryService).listTeamSchedules(TEAM_ID, START, END, USER_ID);
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
                    START_ODT,
                    END_ODT,
                    Boolean.FALSE,
                    "PRACTICE",
                    null, null, null,
                    Boolean.TRUE,
                    null,
                    null,
                    null, null,
                    null,
                    null,
                    null,
                    null, null, false);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("練習");
            verify(scheduleRepository).save(any(ScheduleEntity.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("スケジュール作成_日付不正_例外スロー")
        void スケジュール作成_日付不正_例外スロー() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "練習", null, null,
                    END_ODT, START_ODT, // start > end
                    false, "PRACTICE",
                    null, null, null,
                    false, null, null, null, null, null, null, null, null, null, false);

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
                    START_ODT, END_ODT,
                    false, "PRACTICE",
                    null, null, null,
                    false, null, null, null, null, null, null, null, null, null, false);

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
                    null, null, null, null, null, null, null, null, null);

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
                    null, null, null, null, null, null, null, null, null);

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
            assertThat(result.getContent().title()).isEqualTo("練習");
            assertThat(result.getContent().status()).isEqualTo("SCHEDULED");
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
        @DisplayName("組織スケジュール一覧_正常_QueryServiceに委譲する")
        void 組織スケジュール一覧_正常_QueryServiceに委譲する() {
            // given
            Long ORG_ID = 20L;
            ScheduleResponse stub = ScheduleResponse.builder()
                    .id(SCHEDULE_ID)
                    .content(new ScheduleResponse.ScheduleContentDto("全体集会", "SCHEDULED", "EVENT", null, null))
                    .time(new ScheduleResponse.ScheduleTimeDto(START, END, false))
                    .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                    .academic(new ScheduleResponse.ScheduleAcademicDto(null, null, null))
                    .audit(new ScheduleResponse.ScheduleAuditDto(null, null))
                    .build();
            given(queryService.listOrgSchedules(ORG_ID, START, END, USER_ID))
                    .willReturn(List.of(stub));

            // when
            List<ScheduleResponse> result = scheduleService.listOrgSchedules(ORG_ID, START, END, USER_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent().title()).isEqualTo("全体集会");
            verify(queryService).listOrgSchedules(ORG_ID, START, END, USER_ID);
        }
    }

    // ========================================
    // deleteSchedule with THIS_AND_FOLLOWING
    // ========================================

    @Nested
    @DisplayName("deleteSchedule_THIS_AND_FOLLOWING")
    class DeleteScheduleThisAndFollowing {

        @Test
        @DisplayName("THIS_AND_FOLLOWING削除_繰り返し子スケジュール_RecurrenceServiceに委譲する")
        void THIS_AND_FOLLOWING削除_繰り返し子スケジュール_RecurrenceServiceに委譲する() {
            // given
            ScheduleEntity child = ScheduleEntity.builder()
                    .teamId(TEAM_ID).title("繰り返し練習")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.PRACTICE).visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                    .parentScheduleId(99L) // 親IDあり
                    .isException(false).createdBy(USER_ID).build();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(child));

            // when
            scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_AND_FOLLOWING");

            // then
            verify(scheduleRepository).findById(SCHEDULE_ID);
            verify(recurrenceService).deleteFollowingSchedules(child);
        }

        @Test
        @DisplayName("ALL削除_繰り返し子から親含め全削除_RecurrenceServiceに委譲する")
        void ALL削除_繰り返し子から親含め全削除_RecurrenceServiceに委譲する() {
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

            // when
            scheduleService.deleteSchedule(SCHEDULE_ID, "ALL");

            // then
            assertThat(parent.getDeletedAt()).isNotNull();
            verify(recurrenceService).deleteChildSchedules(99L);
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
                    "個人予定", null, null, START_ODT, END_ODT, false, "OTHER",
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, USER_ID, "PERSONAL", USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("スケジュール作成_組織スコープ_正常作成")
        void スケジュール作成_組織スコープ_正常作成() {
            // given
            Long ORG_ID = 20L;
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "組織イベント", null, null, START_ODT, END_ODT, false, "EVENT",
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false);

            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            ScheduleResponse result = scheduleService.createSchedule(req, ORG_ID, "ORGANIZATION", USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("組織イベント");
        }
    }

    // ========================================
    // createSchedule - タイムゾーン変換
    // ========================================

    @Nested
    @DisplayName("createSchedule_タイムゾーン変換")
    class CreateScheduleTimezoneConversion {

        @Test
        @DisplayName("UTC入力_JST(+9h)に変換してEntityに保存される")
        void UTC入力_JSTに変換してEntityに保存される() {
            // given: UTC 01:00 = JST 10:00
            OffsetDateTime startUtc = OffsetDateTime.of(2026, 4, 1, 1, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime endUtc = OffsetDateTime.of(2026, 4, 1, 3, 0, 0, 0, ZoneOffset.UTC);
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "UTC入力テスト", null, null, startUtc, endUtc, false, "PRACTICE",
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false);

            ScheduleEntity[] saved = new ScheduleEntity[1];
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> {
                        saved[0] = invocation.getArgument(0);
                        return saved[0];
                    });

            // when
            scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID);

            // then: UTC+0h の 01:00 は JST+9h の 10:00 に変換される
            assertThat(saved[0].getStartAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0, 0));
            assertThat(saved[0].getEndAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 12, 0, 0));
        }

        @Test
        @DisplayName("EST入力_JST(+14h)に変換してEntityに保存される")
        void EST入力_JSTに変換してEntityに保存される() {
            // given: EST(UTC-5) 20:00 = JST(UTC+9) 10:00(翌日)
            OffsetDateTime startEst = OffsetDateTime.of(2026, 3, 31, 20, 0, 0, 0, ZoneOffset.ofHours(-5));
            OffsetDateTime endEst = OffsetDateTime.of(2026, 3, 31, 22, 0, 0, 0, ZoneOffset.ofHours(-5));
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "EST入力テスト", null, null, startEst, endEst, false, "EVENT",
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false);

            ScheduleEntity[] saved = new ScheduleEntity[1];
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> {
                        saved[0] = invocation.getArgument(0);
                        return saved[0];
                    });

            // when
            scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID);

            // then: EST 2026-03-31 20:00(UTC-5) = UTC 2026-04-01 01:00 = JST 2026-04-01 10:00
            assertThat(saved[0].getStartAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0, 0));
            assertThat(saved[0].getEndAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 12, 0, 0));
        }

        @Test
        @DisplayName("JST入力_そのままEntityに保存される")
        void JST入力_そのままEntityに保存される() {
            // given: JST 10:00 はそのまま 10:00 として保存される
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "JST入力テスト", null, null, START_ODT, END_ODT, false, "PRACTICE",
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false);

            ScheduleEntity[] saved = new ScheduleEntity[1];
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> {
                        saved[0] = invocation.getArgument(0);
                        return saved[0];
                    });

            // when
            scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID);

            // then: JST 10:00 → 変換後も 10:00
            assertThat(saved[0].getStartAt()).isEqualTo(START);
            assertThat(saved[0].getEndAt()).isEqualTo(END);
        }
    }

    // ========================================
    // getMyCalendar
    // ========================================

    @Nested
    @DisplayName("getMyCalendar")
    class GetMyCalendar {

        @Test
        @DisplayName("横断カレンダー取得_QueryServiceに委譲する")
        void 横断カレンダー取得_QueryServiceに委譲する() {
            // given
            CalendarEntryResponse stub = CalendarEntryResponse.builder()
                    .id(SCHEDULE_ID)
                    .content(new CalendarEntryResponse.CalendarContentDto("個人予定", "OTHER", "SCHEDULED"))
                    .time(new CalendarEntryResponse.CalendarTimeDto(START, END, false))
                    .scope(new CalendarEntryResponse.CalendarScopeDto("PERSONAL", USER_ID, "個人", null))
                    .build();
            given(queryService.getMyCalendar(USER_ID, START, END))
                    .willReturn(List.of(stub));

            // when
            List<CalendarEntryResponse> result = scheduleService.getMyCalendar(USER_ID, START, END);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent().title()).isEqualTo("個人予定");
            verify(queryService).getMyCalendar(USER_ID, START, END);
        }
    }
}
