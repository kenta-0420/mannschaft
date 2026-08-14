package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.AttendanceRequest;
import com.mannschaft.app.schedule.dto.AttendanceResponse;
import com.mannschaft.app.schedule.dto.AttendanceStatsResponse;
import com.mannschaft.app.schedule.dto.AttendanceSummaryResponse;
import com.mannschaft.app.schedule.dto.BulkAttendanceRequest;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.EventSurveyService;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAttendanceService} の単体テスト。
 * 出欠回答・集計・CSV出力・統計を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAttendanceService 単体テスト")
class ScheduleAttendanceServiceTest {

    @Mock
    private ScheduleAttendanceRepository attendanceRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleService scheduleService;

    @Mock
    private EventSurveyService eventSurveyService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private ScheduleDelegationService scheduleDelegationService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private com.mannschaft.app.organization.service.OrganizationMembershipService organizationMembershipService;

    @InjectMocks
    private ScheduleAttendanceService attendanceService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 4, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 1, 12, 0);
    private static final LocalDateTime FUTURE_DEADLINE = LocalDateTime.of(2099, 12, 31, 23, 59);

    private ScheduleEntity createScheduleWithAttendance() {
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
                .attendanceDeadline(FUTURE_DEADLINE)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    private ScheduleEntity createScheduleWithoutAttendance() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("お知らせ")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .isException(false)
                .build();
    }

    private ScheduleAttendanceEntity createAttendanceEntity(AttendanceStatus status) {
        return ScheduleAttendanceEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .userId(USER_ID)
                .status(status)
                .build();
    }

    /** 指定した minResponseRole を持つ TEAM スコープの出欠対象スケジュールを生成する。 */
    private ScheduleEntity createScheduleWithMinResponseRole(MinResponseRole minResponseRole) {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("練習")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .minResponseRole(minResponseRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .attendanceDeadline(FUTURE_DEADLINE)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    /** 指定した minResponseRole を持つ ORGANIZATION スコープの出欠対象スケジュールを生成する（欠陥Z 用）。 */
    private ScheduleEntity createOrgScheduleWithMinResponseRole(MinResponseRole minResponseRole) {
        return createOrgScheduleWithMinResponseRole(minResponseRole, false);
    }

    /** includeSupporters トグルを指定できる ORGANIZATION スコープ出欠対象スケジュール生成（配信＝受信権 用）。 */
    private ScheduleEntity createOrgScheduleWithMinResponseRole(MinResponseRole minResponseRole,
                                                                boolean includeSupporters) {
        return ScheduleEntity.builder()
                .organizationId(ORG_ID)
                .title("組織練習")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .minResponseRole(minResponseRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .includeSupporters(includeSupporters)
                .attendanceDeadline(FUTURE_DEADLINE)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // respondAttendance
    // ========================================

    @Nested
    @DisplayName("respondAttendance")
    class RespondAttendance {

        @Test
        @DisplayName("出欠回答_正常_保存されてイベント発行される")
        void 出欠回答_正常_保存されてイベント発行される() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            // minResponseRole は DB DEFAULT / Entity @Builder.Default により常に MEMBER_PLUS
            // （nullではない・DDL上NOT NULL DEFAULT 'MEMBER_PLUS'）。本テストは認可自体を
            // 検証対象にしていないため、被験者がMEMBER以上を満たす前提で明示的にstubする
            // （検分差し戻し是正: 認可で先に弾かれて意図した分岐に到達しない事故の再発防止）。
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            AttendanceRequest req = new AttendanceRequest("ATTENDING", "参加します", null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("出欠回答_出欠管理対象外_例外スロー")
        void 出欠回答_出欠管理対象外_例外スロー() {
            // given
            ScheduleEntity schedule = createScheduleWithoutAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }

        @Test
        @DisplayName("出欠回答_期限超過_例外スロー")
        void 出欠回答_期限超過_例外スロー() {
            // given
            ScheduleEntity schedule = ScheduleEntity.builder()
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
                    .attendanceDeadline(LocalDateTime.of(2020, 1, 1, 0, 0))
                    .commentOption(CommentOption.OPTIONAL)
                    .isException(false)
                    .build();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            // minResponseRole は既定 MEMBER_PLUS（DDL NOT NULL DEFAULT と同値）。本テストの
            // 検証対象は期限チェックであり認可ではないため、被験者がMEMBER以上を満たす前提で
            // 明示的にstubし、認可で先に弾かれて意図した分岐に到達しない事故を防ぐ。
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_DEADLINE_PASSED);
        }

        @Test
        @DisplayName("出欠回答_コメント必須なのに空_例外スロー")
        void 出欠回答_コメント必須なのに空_例外スロー() {
            // given
            ScheduleEntity schedule = ScheduleEntity.builder()
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
                    .attendanceDeadline(FUTURE_DEADLINE)
                    .commentOption(CommentOption.REQUIRED)
                    .isException(false)
                    .build();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            // minResponseRole は既定 MEMBER_PLUS（DDL NOT NULL DEFAULT と同値）。本テストの
            // 検証対象はコメント必須チェックであり認可ではないため、被験者がMEMBER以上を
            // 満たす前提で明示的にstubし、認可で先に弾かれて意図した分岐に到達しない事故を防ぐ。
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            AttendanceRequest req = new AttendanceRequest("ABSENT", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.COMMENT_REQUIRED);
        }
    }

    // ========================================
    // respondAttendance — 後見切替（GUARDIANSHIP_SWITCH）代理入力スモーク（F08.9 P3c）
    // ========================================

    @Nested
    @DisplayName("respondAttendance 後見切替（GUARDIANSHIP_SWITCH）代理入力")
    class RespondAttendanceGuardianshipSwitch {

        @org.junit.jupiter.api.AfterEach
        void clearSecurityContext() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        @Test
        @DisplayName("切替中_consentId=null_inputSource=GUARDIANSHIP_SWITCH_でNPEなくconsent_id=NULLの記録を保存する")
        void 後見切替_consentIdがnullでも代理入力記録を保存する() {
            // given: 保護者（proxyUserId=300）が子（USER_ID）として acting-as 中。
            //        後見切替では ProxyInputContextFilter が consentId=null・
            //        inputSource=GUARDIANSHIP_SWITCH・storage=固定値で activate する。
            org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .setAuthentication(new org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken("300", null, java.util.List.of()));

            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            // minResponseRole は既定 MEMBER_PLUS（DDL NOT NULL DEFAULT と同値）。本テストは
            // 後見切替の代理入力記録スモークが検証対象であり認可ではないため、respondAttendance
            // に渡る被験者（USER_ID）がMEMBER以上を満たす前提で明示的にstubする。
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // 後見切替モード: consentId=null / inputSource=GUARDIANSHIP_SWITCH /
            //                storage=固定値（ProxyInputContextFilter.SWITCH_STORAGE_LOCATION_NA 相当）。
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(null);
            given(proxyInputContext.getSubjectUserId()).willReturn(USER_ID);
            given(proxyInputContext.getInputSource())
                    .willReturn(ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH.name());
            given(proxyInputContext.getOriginalStorageLocation())
                    .willReturn("N/A (online guardianship switch)");

            // 冪等性チェック: consentId=null では既存記録に当たらない（常に新規保存）。
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    any(), any(), any())).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            AttendanceRequest req = new AttendanceRequest("ATTENDING", "参加します", null);

            // when: NPE / 制約違反なく完了すること（切替中の F14.1 代理入力スモーク）。
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then: 出欠は登録され、proxy_input_records が consent_id=NULL・
            //       inputSource=GUARDIANSHIP_SWITCH で保存される。
            assertThat(result.getStatus()).isEqualTo("ATTENDING");

            ArgumentCaptor<ProxyInputRecordEntity> captor =
                    ArgumentCaptor.forClass(ProxyInputRecordEntity.class);
            verify(proxyInputRecordRepository).save(captor.capture());
            ProxyInputRecordEntity saved = captor.getValue();
            assertThat(saved.getProxyInputConsentId()).isNull();
            assertThat(saved.getSubjectUserId()).isEqualTo(USER_ID);
            assertThat(saved.getProxyUserId()).isEqualTo(300L);
            assertThat(saved.getInputSource())
                    .isEqualTo(ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH);
            assertThat(saved.getOriginalStorageLocation()).isNotNull();
        }
    }

    // ========================================
    // respondAttendance — min_response_role enforcement（F03.1 セキュリティ根治）
    // ========================================

    @Nested
    @DisplayName("respondAttendance min_response_role enforcement")
    class RespondAttendanceMinResponseRole {

        private void stubSaveAndDeadline() {
            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("ADMIN_ONLY_一般MEMBERは回答拒否_COMMON_002")
        void ADMIN_ONLY_一般MEMBERは回答拒否() {
            // given: ADMIN_ONLY スケジュールに一般 MEMBER が回答しようとする
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.ADMIN_ONLY);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ADMIN_ONLY_ADMINは回答可能")
        void ADMIN_ONLY_ADMINは回答可能() {
            // given
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.ADMIN_ONLY);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", "承認", null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("ADMIN_ONLY_SYSTEM_ADMINは横断で回答可能")
        void ADMIN_ONLY_SYSTEM_ADMINは回答可能() {
            // given
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.ADMIN_ONLY);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("MEMBER_PLUS_SUPPORTERは回答拒否_COMMON_002")
        void MEMBER_PLUS_SUPPORTERは回答拒否() {
            // given: MEMBER_PLUS スケジュールに SUPPORTER（MEMBER未満）が回答しようとする
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.MEMBER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("MEMBER_PLUS_MEMBERは回答可能")
        void MEMBER_PLUS_MEMBERは回答可能() {
            // given
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.MEMBER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("SUPPORTER_PLUS_SUPPORTERは回答可能")
        void SUPPORTER_PLUS_SUPPORTERは回答可能() {
            // given: SUPPORTER_PLUS スケジュールに SUPPORTER が回答する
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.SUPPORTER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("SUPPORTER_PLUS_非所属は回答拒否_COMMON_002")
        void SUPPORTER_PLUS_非所属は回答拒否() {
            // given
            ScheduleEntity schedule = createScheduleWithMinResponseRole(MinResponseRole.SUPPORTER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when & then
            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("minResponseRole_null_後方互換で従来どおりメンバー回答可能")
        void minResponseRole_null_後方互換で回答可能() {
            // given: minResponseRole 未設定（移行前データ）。enforcement はスキップされる。
            ScheduleEntity schedule = createScheduleWithMinResponseRole(null);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then: AccessControlService に一切問い合わせないこと（後方互換）
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
            org.mockito.Mockito.verifyNoInteractions(accessControlService);
        }

        // ---- 欠陥Z 根治: 組織スケジュールの配下メンバー救済（真因④） ----

        @Test
        @DisplayName("欠陥Z_ORG_MEMBER_PLUS_配下チームのみ所属MEMBERは救済されて回答可能")
        void 欠陥Z_組織MEMBER_PLUS_配下メンバー救済() {
            // given: 組織スケジュール(MEMBER_PLUS)に、組織に直接ロールを持たない配下チームのみ所属MEMBERが回答。
            // 直接ロール解決は null → hasRoleOrAbove(...,"MEMBER")=false。配下救済フォールバックで回答可能。
            ScheduleEntity schedule = createOrgScheduleWithMinResponseRole(MinResponseRole.MEMBER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);
            // 配下救済: MEMBER 要求段は includeSupporters=false（純 SUPPORTER 除外）で配下 MEMBER を許容
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION", false)).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            // when
            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);

            // then
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("欠陥Z_ORG_MEMBER_PLUS_配下にもいない者はフォールバックでもfalse_COMMON_002")
        void 欠陥Z_組織MEMBER_PLUS_配下にもいない者は拒否() {
            ScheduleEntity schedule = createOrgScheduleWithMinResponseRole(MinResponseRole.MEMBER_PLUS);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION", false)).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("欠陥Z_ORG_ADMIN_ONLY_管理者要求段では配下メンバーを救済しない_COMMON_002")
        void 欠陥Z_組織ADMIN_ONLY_配下メンバー救済なし() {
            // given: 管理者要求段。配下メンバーは組織 ADMIN ではないため救済しない（御裁可④）。
            ScheduleEntity schedule = createOrgScheduleWithMinResponseRole(MinResponseRole.ADMIN_ONLY);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            // 管理者要求段ではフォールバック（配下救済）を一切呼ばないこと
            org.mockito.Mockito.verify(accessControlService, org.mockito.Mockito.never())
                    .isMemberOrDescendant(org.mockito.ArgumentMatchers.eq(USER_ID),
                            org.mockito.ArgumentMatchers.eq(ORG_ID),
                            org.mockito.ArgumentMatchers.eq("ORGANIZATION"),
                            org.mockito.ArgumentMatchers.anyBoolean());
        }

        // ---- 配信＝受信権 統一: SUPPORTER_PLUS のトグル準拠救済（関所(3)回答） ----

        @Test
        @DisplayName("配信統一_ORG_SUPPORTER_PLUS_トグルON_配下SUPPORTERは配信母集団として救済され回答可能")
        void 配信統一_組織SUPPORTER_PLUS_トグルON_配下救済() {
            // given: includeSupporters=true の組織出欠(SUPPORTER_PLUS)。配下 SUPPORTER は配信母集団＝回答可。
            ScheduleEntity schedule = createOrgScheduleWithMinResponseRole(MinResponseRole.SUPPORTER_PLUS, true);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER")).willReturn(false);
            // トグル ON のためトグル準拠（includeSupporters=true）で救済される
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION", true)).willReturn(true);
            stubSaveAndDeadline();

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            AttendanceResponse result = attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req);
            assertThat(result.getStatus()).isEqualTo("ATTENDING");
        }

        @Test
        @DisplayName("配信統一_ORG_SUPPORTER_PLUS_トグルOFF_配下純SUPPORTERは母集団外で回答不可_COMMON_002")
        void 配信統一_組織SUPPORTER_PLUS_トグルOFF_純SUPPORTER拒否() {
            // given: includeSupporters=false の組織出欠(SUPPORTER_PLUS)。配下純 SUPPORTER は母集団外＝回答不可。
            ScheduleEntity schedule = createOrgScheduleWithMinResponseRole(MinResponseRole.SUPPORTER_PLUS, false);
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER")).willReturn(false);
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION", false)).willReturn(false);

            AttendanceRequest req = new AttendanceRequest("ATTENDING", null, null);

            assertThatThrownBy(() -> attendanceService.respondAttendance(SCHEDULE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // getAttendances
    // ========================================

    @Nested
    @DisplayName("getAttendances")
    class GetAttendances {

        @Test
        @DisplayName("出欠一覧取得_正常_一覧を返す")
        void 出欠一覧取得_正常_一覧を返す() {
            // given: checkScopeViewAccess は entity 由来 scope の per-scope 認可を担う
            // ScheduleService 側の void メソッド（モックのためデフォルトで no-op）。
            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            List<AttendanceResponse> result = attendanceService.getAttendances(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ATTENDING");
            verify(scheduleService).checkScopeViewAccess(SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("出欠一覧取得_非権限者_COMMON_002")
        void 出欠一覧取得_非権限者_COMMON_002() {
            // given
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scheduleService).checkScopeViewAccess(SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> attendanceService.getAttendances(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(attendanceRepository, never()).findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID);
        }
    }

    // ========================================
    // getAttendanceSummary
    // ========================================

    @Nested
    @DisplayName("getAttendanceSummary")
    class GetAttendanceSummary {

        @Test
        @DisplayName("出欠サマリー取得_正常_集計結果を返す")
        void 出欠サマリー取得_正常_集計結果を返す() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            Object[] row1 = new Object[]{AttendanceStatus.ATTENDING, 3L};
            Object[] row2 = new Object[]{AttendanceStatus.ABSENT, 1L};
            given(attendanceRepository.countByScheduleIdGroupByStatus(SCHEDULE_ID))
                    .willReturn(List.of(row1, row2));

            // when
            AttendanceSummaryResponse result = attendanceService.getAttendanceSummary(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getAttending()).isEqualTo(3);
            assertThat(result.getAbsent()).isEqualTo(1);
            assertThat(result.getTotal()).isEqualTo(4);
        }

        @Test
        @DisplayName("出欠サマリー取得_scope外の利用者_COMMON_002")
        void 出欠サマリー取得_scope外の利用者_COMMON_002() {
            // given: checkScopeViewAccess が entity 由来 scope で弾く
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scheduleService).checkScopeViewAccess(SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> attendanceService.getAttendanceSummary(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // 出席率統計の認可（認可根治 Wave6）
    // ========================================

    @Nested
    @DisplayName("出席率統計の認可（認可根治 Wave6）")
    class AttendanceStatsAuthorization {

        /** 期間フィクスチャ。文字列リテラルでなく LocalDateTime で bind する（TZ ズレ事故の回避）。 */
        private static final LocalDateTime FROM = LocalDateTime.of(2026, 4, 1, 0, 0);
        private static final LocalDateTime TO = LocalDateTime.of(2026, 4, 30, 23, 59);

        @Test
        @DisplayName("チーム統計_チーム管理者でない_COMMON_002")
        void チーム統計_チーム管理者でない_COMMON_002() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> attendanceService.getTeamAttendanceStats(
                    TEAM_ID, FROM, TO, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            // 認可前にリポジトリを引いていないこと（漏洩経路が残っていないこと）
            org.mockito.Mockito.verifyNoInteractions(userRoleRepository);
        }

        @Test
        @DisplayName("組織統計_組織管理者でない_COMMON_002")
        void 組織統計_組織管理者でない_COMMON_002() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> attendanceService.getOrgAttendanceStats(
                    ORG_ID, FROM, TO, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            org.mockito.Mockito.verifyNoInteractions(userRoleRepository);
        }

        @Test
        @DisplayName("チーム統計_正常_チーム管理者は取得できる")
        void チーム統計_正常_チーム管理者は取得できる() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(userRoleRepository.findByTeamId(org.mockito.ArgumentMatchers.eq(TEAM_ID),
                    org.mockito.ArgumentMatchers.any()))
                    .willReturn(org.springframework.data.domain.Page.empty());
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(TEAM_ID, FROM, TO))
                    .willReturn(List.of());

            assertThat(attendanceService.getTeamAttendanceStats(TEAM_ID, FROM, TO, USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("組織統計_正常_SYSTEM_ADMINは横断で取得できる")
        void 組織統計_正常_SYSTEM_ADMINは横断で取得できる() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(userRoleRepository.findByOrganizationId(org.mockito.ArgumentMatchers.eq(ORG_ID),
                    org.mockito.ArgumentMatchers.any()))
                    .willReturn(org.springframework.data.domain.Page.empty());
            given(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(ORG_ID, FROM, TO))
                    .willReturn(List.of());

            assertThat(attendanceService.getOrgAttendanceStats(ORG_ID, FROM, TO, USER_ID)).isEmpty();

            // SYSTEM_ADMIN は per-scope 判定を通さない
            org.mockito.Mockito.verify(accessControlService, org.mockito.Mockito.never())
                    .checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        }
    }

    // ========================================
    // bulkUpdateAttendances
    // ========================================

    @Nested
    @DisplayName("bulkUpdateAttendances")
    class BulkUpdateAttendances {

        @Test
        @DisplayName("一括更新_正常_出欠が更新される")
        void 一括更新_正常_出欠が更新される() {
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.UNDECIDED);
            given(attendanceRepository.findByScheduleIdAndUserId(SCHEDULE_ID, USER_ID))
                    .willReturn(Optional.of(attendance));
            given(attendanceRepository.save(any(ScheduleAttendanceEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            BulkAttendanceRequest req = new BulkAttendanceRequest(
                    List.of(new BulkAttendanceRequest.BulkAttendanceItem(USER_ID, "ATTENDING", "管理者承認")));

            // when
            attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req, USER_ID);

            // then
            verify(attendanceRepository).save(any(ScheduleAttendanceEntity.class));
            verify(scheduleService).checkScopeAdminAccess(SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("一括更新_出欠管理対象外_例外スロー")
        void 一括更新_出欠管理対象外_例外スロー() {
            // given
            ScheduleEntity schedule = createScheduleWithoutAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            BulkAttendanceRequest req = new BulkAttendanceRequest(
                    List.of(new BulkAttendanceRequest.BulkAttendanceItem(USER_ID, "ATTENDING", null)));

            // when & then
            assertThatThrownBy(() -> attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
        }

        @Test
        @DisplayName("一括更新_非権限者_COMMON_002")
        void 一括更新_非権限者_COMMON_002() {
            // given: checkScopeAdminAccess（ScheduleService 側）が COMMON_002 を投げるケース
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scheduleService).checkScopeAdminAccess(SCHEDULE_ID, USER_ID);

            BulkAttendanceRequest req = new BulkAttendanceRequest(
                    List.of(new BulkAttendanceRequest.BulkAttendanceItem(USER_ID, "ATTENDING", null)));

            // when & then
            assertThatThrownBy(() -> attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(attendanceRepository, never()).save(any(ScheduleAttendanceEntity.class));
        }
    }

    // ========================================
    // exportAttendancesCsv
    // ========================================

    @Nested
    @DisplayName("exportAttendancesCsv")
    class ExportAttendancesCsv {

        @Test
        @DisplayName("CSV出力_正常_ヘッダーとデータを含む")
        void CSV出力_正常_ヘッダーとデータを含む() {
            // given: checkScopeViewAccess は entity 由来 scope の per-scope 認可を担う
            // ScheduleService 側の void メソッド（モックのためデフォルトで no-op）。
            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            attendance.respond(AttendanceStatus.ATTENDING, "参加します");
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            String csv = attendanceService.exportAttendancesCsv(SCHEDULE_ID, USER_ID);

            // then
            assertThat(csv).startsWith("ユーザーID,ステータス,コメント,回答日時");
            assertThat(csv).contains("ATTENDING");
            verify(scheduleService).checkScopeViewAccess(SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("CSV出力_非権限者_COMMON_002")
        void CSV出力_非権限者_COMMON_002() {
            // given
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scheduleService).checkScopeViewAccess(SCHEDULE_ID, USER_ID);

            // when & then
            assertThatThrownBy(() -> attendanceService.exportAttendancesCsv(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    // ========================================
    // generateAttendanceRecords
    // ========================================

    @Nested
    @DisplayName("generateAttendanceRecords")
    class GenerateAttendanceRecords {

        @Test
        @DisplayName("出欠レコード生成_3名分_saveAllでバッチ保存される")
        void 出欠レコード生成_3名分_saveAllでバッチ保存される() {
            // given: 規模対応 Tier2 で per-user save → saveAll バッチ INSERT に変更済み。
            List<Long> memberIds = List.of(1L, 2L, 3L);

            // when
            attendanceService.generateAttendanceRecords(SCHEDULE_ID, memberIds);

            // then: saveAll が 1 回・3件のエンティティで呼ばれる（per-user save は使わない）
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ScheduleAttendanceEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(attendanceRepository, org.mockito.Mockito.times(1)).saveAll(captor.capture());
            verify(attendanceRepository, org.mockito.Mockito.never())
                    .save(any(ScheduleAttendanceEntity.class));
            assertThat(captor.getValue()).hasSize(3);
        }
    }

    // ========================================
    // getMyAttendanceStats
    // ========================================

    @Nested
    @DisplayName("getMyAttendanceStats")
    class GetMyAttendanceStats {

        @Test
        @DisplayName("個人出席統計_出欠なし_出席率0を返す")
        void 個人出席統計_出欠なし_出席率0を返す() {
            // given
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // when
            AttendanceStatsResponse result = attendanceService.getMyAttendanceStats(USER_ID, START, END);

            // then
            assertThat(result.getTotalSchedules()).isZero();
            assertThat(result.getAttendanceRate()).isZero();
        }
    }

    // ========================================
    // getAttendanceTeamBreakdown（(B) フェーズB・出欠のチーム別内訳 by_team）
    // ========================================

    @Nested
    @DisplayName("getAttendanceTeamBreakdown")
    class GetAttendanceTeamBreakdown {

        private static final Long ORG_SCHEDULE_ID = 30L;
        private static final Long TEAM_A = 1L;
        private static final Long TEAM_B = 2L;

        /** team_breakdown_enabled / include_supporters を指定した組織出欠スケジュールを生成する。 */
        private ScheduleEntity orgSchedule(boolean teamBreakdownEnabled, boolean includeSupporters) {
            return ScheduleEntity.builder()
                    .organizationId(ORG_ID)
                    .title("組織練習")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .includeSupporters(includeSupporters)
                    .teamBreakdownEnabled(teamBreakdownEnabled)
                    .commentOption(CommentOption.OPTIONAL)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();
        }

        private ScheduleAttendanceEntity attendance(Long userId, AttendanceStatus status) {
            return ScheduleAttendanceEntity.builder()
                    .scheduleId(ORG_SCHEDULE_ID)
                    .userId(userId)
                    .status(status)
                    .build();
        }

        /** countByScheduleIdGroupByStatus が返す Object[]{status, count(Long)} を組み立てる。 */
        private List<Object[]> statusCounts(java.util.Map<AttendanceStatus, Long> counts) {
            List<Object[]> rows = new java.util.ArrayList<>();
            counts.forEach((status, count) -> rows.add(new Object[]{status, count}));
            return rows;
        }

        @Test
        @DisplayName("番人③: トグルOFFはby_teamを省略（null）＝従来挙動・totalは返す")
        void トグルOFFはbyTeam省略() {
            // given: team_breakdown_enabled = false
            given(scheduleService.getSchedule(ORG_SCHEDULE_ID))
                    .willReturn(orgSchedule(false, false));
            given(attendanceRepository.countByScheduleIdGroupByStatus(ORG_SCHEDULE_ID))
                    .willReturn(statusCounts(java.util.Map.of(
                            AttendanceStatus.ATTENDING, 3L,
                            AttendanceStatus.ABSENT, 1L)));

            // when
            var result = attendanceService.getAttendanceTeamBreakdown(ORG_SCHEDULE_ID);

            // then: byTeam は null（省略）、total は実人数で返る
            assertThat(result.getByTeam()).isNull();
            assertThat(result.getTotal().attending()).isEqualTo(3);
            assertThat(result.getTotal().absent()).isEqualTo(1);
            // トグル OFF では母集団解決（越境窓口）を呼ばない
            org.mockito.Mockito.verifyNoInteractions(organizationMembershipService);
        }

        @Test
        @DisplayName("番人④⑤: トグルONでby_team算出・totalはDISTINCT実人数・複数チーム所属は全チーム計上(合計>total)・team_id=null枠")
        void トグルONで全チーム計上_totalはDISTINCT() {
            // given: 3 ユーザー
            //   u101: TEAM_A のみ → ATTENDING
            //   u102: TEAM_A と TEAM_B 両方所属 → ABSENT（両チームに計上＝重複）
            //   u103: 組織直属（team_id=null 枠）→ PARTIAL
            given(scheduleService.getSchedule(ORG_SCHEDULE_ID))
                    .willReturn(orgSchedule(true, false));

            // total（実人数）: ATTENDING=1, ABSENT=1, PARTIAL=1 → DISTINCT 3 名
            given(attendanceRepository.countByScheduleIdGroupByStatus(ORG_SCHEDULE_ID))
                    .willReturn(statusCounts(java.util.Map.of(
                            AttendanceStatus.ATTENDING, 1L,
                            AttendanceStatus.ABSENT, 1L,
                            AttendanceStatus.PARTIAL, 1L)));

            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(ORG_SCHEDULE_ID))
                    .willReturn(List.of(
                            attendance(101L, AttendanceStatus.ATTENDING),
                            attendance(102L, AttendanceStatus.ABSENT),
                            attendance(103L, AttendanceStatus.PARTIAL)));

            // 母集団解決（越境窓口）: u102 は TEAM_A/TEAM_B 両方、u103 は組織直属（teamId=null）
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, false))
                    .willReturn(java.util.Map.of(
                            101L, List.of(new com.mannschaft.app.organization.service.OrganizationMembershipService.TeamRef(TEAM_A, "Aチーム")),
                            102L, List.of(
                                    new com.mannschaft.app.organization.service.OrganizationMembershipService.TeamRef(TEAM_A, "Aチーム"),
                                    new com.mannschaft.app.organization.service.OrganizationMembershipService.TeamRef(TEAM_B, "Bチーム")),
                            103L, List.of(new com.mannschaft.app.organization.service.OrganizationMembershipService.TeamRef(null, null))));

            // when
            var result = attendanceService.getAttendanceTeamBreakdown(ORG_SCHEDULE_ID);

            // then: total は DISTINCT 実人数（各 1）
            assertThat(result.getTotal().attending()).isEqualTo(1);
            assertThat(result.getTotal().absent()).isEqualTo(1);
            assertThat(result.getTotal().partial()).isEqualTo(1);

            assertThat(result.getByTeam()).isNotNull();

            // TEAM_A: u101(ATTENDING) + u102(ABSENT)
            var teamA = result.getByTeam().stream()
                    .filter(i -> TEAM_A.equals(i.teamId())).findFirst().orElseThrow();
            assertThat(teamA.teamName()).isEqualTo("Aチーム");
            assertThat(teamA.attending()).isEqualTo(1);
            assertThat(teamA.absent()).isEqualTo(1);

            // TEAM_B: u102(ABSENT) ← 複数チーム所属の重複計上
            var teamB = result.getByTeam().stream()
                    .filter(i -> TEAM_B.equals(i.teamId())).findFirst().orElseThrow();
            assertThat(teamB.absent()).isEqualTo(1);

            // team_id=null 枠（組織直接メンバー）: u103(PARTIAL)
            var orgDirect = result.getByTeam().stream()
                    .filter(i -> i.teamId() == null).findFirst().orElseThrow();
            assertThat(orgDirect.partial()).isEqualTo(1);

            // 御裁可A: by_team 各チームの「のべ人数」合計 ≧ total（実人数）。
            //   by_team のべ = TEAM_A(2) + TEAM_B(1) + null枠(1) = 4 > total 実人数 3
            int byTeamTotal = result.getByTeam().stream()
                    .mapToInt(i -> i.attending() + i.partial() + i.absent() + i.undecided())
                    .sum();
            int realTotal = result.getTotal().attending() + result.getTotal().partial()
                    + result.getTotal().absent() + result.getTotal().undecided();
            assertThat(byTeamTotal).isGreaterThan(realTotal);
            assertThat(byTeamTotal).isEqualTo(4);
            assertThat(realTotal).isEqualTo(3);
        }

        @Test
        @DisplayName("番人②: team_id=null枠が組織直接メンバーを拾う")
        void teamIdNull枠が組織直接メンバーを拾う() {
            given(scheduleService.getSchedule(ORG_SCHEDULE_ID))
                    .willReturn(orgSchedule(true, false));
            given(attendanceRepository.countByScheduleIdGroupByStatus(ORG_SCHEDULE_ID))
                    .willReturn(statusCounts(java.util.Map.of(AttendanceStatus.ATTENDING, 1L)));
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(ORG_SCHEDULE_ID))
                    .willReturn(List.of(attendance(200L, AttendanceStatus.ATTENDING)));
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, false))
                    .willReturn(java.util.Map.of(200L, List.of(
                            new com.mannschaft.app.organization.service.OrganizationMembershipService.TeamRef(null, null))));

            var result = attendanceService.getAttendanceTeamBreakdown(ORG_SCHEDULE_ID);

            assertThat(result.getByTeam()).hasSize(1);
            var orgDirect = result.getByTeam().get(0);
            assertThat(orgDirect.teamId()).isNull();
            assertThat(orgDirect.attending()).isEqualTo(1);
        }

        @Test
        @DisplayName("includeSupportersトグルが母集団解決へ伝播する")
        void includeSupportersが母集団解決へ伝播() {
            given(scheduleService.getSchedule(ORG_SCHEDULE_ID))
                    .willReturn(orgSchedule(true, true)); // includeSupporters=true
            given(attendanceRepository.countByScheduleIdGroupByStatus(ORG_SCHEDULE_ID))
                    .willReturn(List.of());
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(ORG_SCHEDULE_ID))
                    .willReturn(List.of());
            given(organizationMembershipService.resolveMemberTeams(ORG_ID, true))
                    .willReturn(java.util.Map.of());

            attendanceService.getAttendanceTeamBreakdown(ORG_SCHEDULE_ID);

            // includeSupporters=true で母集団解決が呼ばれることを検証
            verify(organizationMembershipService).resolveMemberTeams(ORG_ID, true);
        }
    }
}
