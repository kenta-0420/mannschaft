package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private AccessControlService accessControlService;

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
        @DisplayName("アクセスチェック付き取得_F00 assertCanView が通ればそのまま許可")
        void アクセスチェック付き取得_assertCanViewが通れば許可() {
            // given: CMP-017b で OR 迂回路を撤去したため、閲覧判定は assertCanView 一本化。
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when
            ScheduleEntity result = scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getTitle()).isEqualTo("練習");
            verify(contentVisibilityChecker).assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);
            // OR 迂回路は存在しない: 配信母集団判定（越境窓口）は一切呼ばない
            org.mockito.Mockito.verifyNoInteractions(organizationMembershipService);
        }

        @Test
        @DisplayName("番人4（反転）: 組織スケジュールで配信母集団に属していても閾値を満たさねば403でdenyされる")
        void 番人4_配信母集団に属していても閾値未達ならdeny() {
            // given: 組織スケジュール。旧実装は「canView=false でも配信母集団なら200で開ける」
            // という OR 迂回路（脆弱性そのもの）を持っていた。CMP-017b 第三隊がこれを撤去し、
            // 書込時の不変条件（includeSupporters=TRUE ⇒ minViewRole ∈ {ANYONE, SUPPORTER_PLUS}）
            // により配信母集団は必ず閲覧閾値も満たすようにした。
            // ここでは「配信母集団に居るはず」の状況でも assertCanView が拒否するなら
            // 例外が伝播し、200 では絶対に開かないことを固定する（脆弱性の再発防止）。
            org.mockito.Mockito.doThrow(new BusinessException(
                            com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                    .when(contentVisibilityChecker)
                    .assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
            // OR 迂回路は存在しない: 配信母集団判定（越境窓口）を経由して閲覧許可へ迂回することはない
            org.mockito.Mockito.verifyNoInteractions(organizationMembershipService);
            // 迂回路が無い以上、拒否された時点で findById（本体取得）へも進まない
            org.mockito.Mockito.verify(scheduleRepository, never()).findById(SCHEDULE_ID);
        }

        @Test
        @DisplayName("番人5: 組織にも配下にも無関係なユーザーは assertCanView へ委譲され例外（403相当）")
        void 番人5_無関係ユーザーは例外() {
            // given: 組織スケジュール。assertCanView が deny する = 閲覧不可。
            org.mockito.Mockito.doThrow(new BusinessException(
                            com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                    .when(contentVisibilityChecker)
                    .assertCanView(ReferenceType.SCHEDULE, SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> scheduleService.getScheduleWithAccessCheck(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("チームスケジュール_assertCanViewが拒否すれば例外")
        void チームスケジュール_assertCanViewが拒否すれば例外() {
            // given: TEAM スケジュール（orgId null）。配信母集団の概念は ORG のみのため無関係。
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
                    null, null, false, false);

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
                    false, null, null, null, null, null, null, null, null, null, false, false);

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
                    false, null, null, null, null, null, null, null, null, null, false, false);

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(req, TEAM_ID, "INVALID", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_SCOPE);
        }

        @Test
        @DisplayName("スケジュール作成_非権限者_COMMON_002")
        void スケジュール作成_非権限者_COMMON_002() {
            // given
            CreateScheduleRequest req = new CreateScheduleRequest(
                    "練習", null, null,
                    START_ODT, END_ODT,
                    false, "PRACTICE",
                    null, null, null,
                    false, null, null, null, null, null, null, null, null, null, false, false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // when & then
            assertThatThrownBy(() -> scheduleService.createSchedule(req, TEAM_ID, "TEAM", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ScheduleEntity.class));
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

        @Test
        @DisplayName("スケジュール更新_非権限者_COMMON_002")
        void スケジュール更新_非権限者_COMMON_002() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            UpdateScheduleRequest req = new UpdateScheduleRequest(
                    "更新", null, null,
                    null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> scheduleService.updateSchedule(SCHEDULE_ID, req, "THIS_ONLY", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ScheduleEntity.class));
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
            scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY", USER_ID);

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
            assertThatThrownBy(() -> scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }

        @Test
        @DisplayName("スケジュール削除_非権限者_COMMON_002")
        void スケジュール削除_非権限者_COMMON_002() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // when & then
            assertThatThrownBy(() -> scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ScheduleEntity.class));
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

        @Test
        @DisplayName("スケジュールキャンセル_非権限者_COMMON_002")
        void スケジュールキャンセル_非権限者_COMMON_002() {
            // given
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            // when & then
            assertThatThrownBy(() -> scheduleService.cancelSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(scheduleRepository, never()).save(any(ScheduleEntity.class));
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
    // checkScopeAdminAccess / checkScopeViewAccess（認可根治 Wave3-B6）
    //
    // duplicateSchedule 自体（ScheduleCrossRefService.acceptInvitation からも呼ばれる共有メソッド）
    // には認可を持たせず、public な複製 API 入口（Org/TeamScheduleController）で BOLA 是正のために
    // 呼び出す公開メソッド。update/delete/cancel/bulkUpdateAttendances/getAttendances の内部実装にも
    // 使われる TEAM/ORGANIZATION/PERSONAL 分岐ロジックをここで直接検証する。
    // ========================================

    @Nested
    @DisplayName("checkScopeAdminAccess / checkScopeViewAccess")
    class CheckScopeAccess {

        private static final Long ORG_ID = 20L;
        private static final Long OTHER_USER_ID = 999L;

        private ScheduleEntity createOrgScheduleEntity() {
            return ScheduleEntity.builder()
                    .organizationId(ORG_ID)
                    .title("組織イベント")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.EVENT)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();
        }

        private ScheduleEntity createPersonalScheduleEntity(Long ownerUserId) {
            return ScheduleEntity.builder()
                    .userId(ownerUserId)
                    .title("個人予定")
                    .startAt(START).endAt(END).allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .createdBy(ownerUserId)
                    .build();
        }

        @Test
        @DisplayName("checkScopeAdminAccess_TEAMスケジュール_ADMINならcheckAdminOrAboveを通過")
        void checkScopeAdminAccess_TEAM_ADMIN許可() {
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            scheduleService.checkScopeAdminAccess(SCHEDULE_ID, USER_ID);

            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("checkScopeAdminAccess_ORGANIZATIONスケジュール_非ADMINはCOMMON_002")
        void checkScopeAdminAccess_ORG_非ADMIN拒否() {
            ScheduleEntity entity = createOrgScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("checkScopeAdminAccess_PERSONALスケジュール_所有者本人は許可")
        void checkScopeAdminAccess_PERSONAL_所有者許可() {
            ScheduleEntity entity = createPersonalScheduleEntity(USER_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            scheduleService.checkScopeAdminAccess(SCHEDULE_ID, USER_ID);

            // PERSONAL は checkAdminOrAbove を経由しない（membership系APIにPERSONALを渡すと500になるため）
            verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
        }

        @Test
        @DisplayName("checkScopeAdminAccess_PERSONALスケジュール_他人はCOMMON_002（BOLA是正）")
        void checkScopeAdminAccess_PERSONAL_他人拒否() {
            ScheduleEntity entity = createPersonalScheduleEntity(OTHER_USER_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("checkScopeAdminAccess_SYSTEM_ADMINは短絡で許可")
        void checkScopeAdminAccess_SYSTEM_ADMIN短絡() {
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            scheduleService.checkScopeAdminAccess(SCHEDULE_ID, USER_ID);

            verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
        }

        @Test
        @DisplayName("checkScopeViewAccess_TEAMスケジュール_checkMembershipを呼ぶ")
        void checkScopeViewAccess_TEAM_checkMembership呼び出し() {
            ScheduleEntity entity = createTeamScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            scheduleService.checkScopeViewAccess(SCHEDULE_ID, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("checkScopeViewAccess_PERSONALスケジュール_他人はCOMMON_002")
        void checkScopeViewAccess_PERSONAL_他人拒否() {
            ScheduleEntity entity = createPersonalScheduleEntity(OTHER_USER_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> scheduleService.checkScopeViewAccess(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
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
            scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_AND_FOLLOWING", USER_ID);

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
            scheduleService.deleteSchedule(SCHEDULE_ID, "ALL", USER_ID);

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
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false, false);

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
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false, false);

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
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false, false);

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
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false, false);

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
                    null, null, null, false, null, null, null, null, null, null, null, null, null, false, false);

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
