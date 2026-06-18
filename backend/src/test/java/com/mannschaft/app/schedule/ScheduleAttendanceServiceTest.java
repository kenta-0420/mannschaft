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
            // 配下救済: isMemberOrDescendant が true（純 SUPPORTER 除外版で配下 MEMBER を許容）
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
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
            given(accessControlService.isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

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
                    .isMemberOrDescendant(USER_ID, ORG_ID, "ORGANIZATION");
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
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            List<AttendanceResponse> result = attendanceService.getAttendances(SCHEDULE_ID);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo("ATTENDING");
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
            AttendanceSummaryResponse result = attendanceService.getAttendanceSummary(SCHEDULE_ID);

            // then
            assertThat(result.getAttending()).isEqualTo(3);
            assertThat(result.getAbsent()).isEqualTo(1);
            assertThat(result.getTotal()).isEqualTo(4);
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
            attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req);

            // then
            verify(attendanceRepository).save(any(ScheduleAttendanceEntity.class));
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
            assertThatThrownBy(() -> attendanceService.bulkUpdateAttendances(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.ATTENDANCE_NOT_REQUIRED);
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
            // given
            ScheduleEntity schedule = createScheduleWithAttendance();
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(schedule);

            ScheduleAttendanceEntity attendance = createAttendanceEntity(AttendanceStatus.ATTENDING);
            attendance.respond(AttendanceStatus.ATTENDING, "参加します");
            given(attendanceRepository.findByScheduleIdOrderByUserIdAsc(SCHEDULE_ID))
                    .willReturn(List.of(attendance));

            // when
            String csv = attendanceService.exportAttendancesCsv(SCHEDULE_ID);

            // then
            assertThat(csv).startsWith("ユーザーID,ステータス,コメント,回答日時");
            assertThat(csv).contains("ATTENDING");
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
}
