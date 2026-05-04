package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableShareTargetEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableShareTargetRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableShareTargetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 5 個人時間割共有先サービスのユニットテスト。
 *
 * <p>主要シナリオ:</p>
 * <ul>
 *   <li>家族チーム以外への共有指定は 422 (SHARE_TARGET_NOT_FAMILY_TEAM)</li>
 *   <li>非メンバー家族チームへの共有指定は 403 (SHARE_TARGET_NOT_TEAM_MEMBER)</li>
 *   <li>3件超過は 409 (SHARE_TARGET_LIMIT_EXCEEDED)</li>
 *   <li>重複は 409 (SHARE_TARGET_DUPLICATED)</li>
 *   <li>追加成功時に visibility が PRIVATE → FAMILY_SHARED に切替＆監査ログ2件</li>
 *   <li>削除で残件0なら visibility が FAMILY_SHARED → PRIVATE に戻り監査ログ2件</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableShareTargetService ユニットテスト")
class PersonalTimetableShareTargetServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;
    private static final Long FAMILY_TEAM_ID = 50L;
    private static final Long NON_FAMILY_TEAM_ID = 51L;

    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private PersonalTimetableShareTargetRepository shareTargetRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PersonalTimetableShareTargetService service;

    private PersonalTimetableEntity privateTimetable;
    private PersonalTimetableEntity sharedTimetable;
    private TeamEntity familyTeam;
    private TeamEntity nonFamilyTeam;

    @BeforeEach
    void setUp() {
        privateTimetable = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("テスト")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();

        sharedTimetable = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("テスト2")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.FAMILY_SHARED)
                .weekPatternEnabled(false)
                .build();

        familyTeam = TeamEntity.builder()
                .name("我が家")
                .template("family")
                .visibility(TeamEntity.Visibility.PRIVATE)
                .supporterEnabled(false)
                .build();

        nonFamilyTeam = TeamEntity.builder()
                .name("塾")
                .template("school")
                .visibility(TeamEntity.Visibility.PRIVATE)
                .supporterEnabled(false)
                .build();
    }

    // ==================== add ====================

    @Nested
    @DisplayName("add")
    class AddTest {

        @Test
        @DisplayName("正常系：PRIVATE → FAMILY_SHARED へ切替＋監査ログ2件発火")
        void add_正常系_visibility切替() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(0L);
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(false);
            given(shareTargetRepository.save(any(PersonalTimetableShareTargetEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID);

            // visibility が FAMILY_SHARED に保存される
            ArgumentCaptor<PersonalTimetableEntity> ptCaptor =
                    ArgumentCaptor.forClass(PersonalTimetableEntity.class);
            verify(personalTimetableRepository, times(1)).save(ptCaptor.capture());
            assertThat(ptCaptor.getValue().getVisibility())
                    .isEqualTo(PersonalTimetableVisibility.FAMILY_SHARED);

            // 監査ログ: share_added と visibility_changed の2件
            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.share_added"), eq(USER_ID), isNull(),
                    eq(FAMILY_TEAM_ID), isNull(), isNull(), isNull(), isNull(), anyString());
            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.visibility_changed"), eq(USER_ID), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("正常系：既に FAMILY_SHARED ならば visibility 変更なし、監査ログは share_added のみ")
        void add_既にFAMILY_SHARED_visibility不変() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(sharedTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(1L);
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(false);
            given(shareTargetRepository.save(any(PersonalTimetableShareTargetEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID);

            verify(personalTimetableRepository, never()).save(any());
            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.share_added"), eq(USER_ID), isNull(),
                    eq(FAMILY_TEAM_ID), isNull(), isNull(), isNull(), isNull(), anyString());
            verify(auditLogService, never()).record(
                    eq("personal_timetable.visibility_changed"),
                    any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("チームが見つからない場合は 404 (SHARE_TARGET_TEAM_NOT_FOUND)")
        void add_チームなし_404() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_TEAM_NOT_FOUND);
        }

        @Test
        @DisplayName("家族テンプレ以外なら 422 (SHARE_TARGET_NOT_FAMILY_TEAM)")
        void add_非家族チーム_422() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(NON_FAMILY_TEAM_ID))
                    .willReturn(Optional.of(nonFamilyTeam));

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, NON_FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_NOT_FAMILY_TEAM);
        }

        @Test
        @DisplayName("自分が家族チームのメンバーでない場合は 403 (SHARE_TARGET_NOT_TEAM_MEMBER)")
        void add_非メンバー_403() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, FAMILY_TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_NOT_TEAM_MEMBER);
        }

        @Test
        @DisplayName("3件到達済みなら 409 (SHARE_TARGET_LIMIT_EXCEEDED)")
        void add_上限超過_409() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(3L);

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("重複登録は 409 (SHARE_TARGET_DUPLICATED)")
        void add_重複_409() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(privateTimetable));
            given(teamRepository.findById(FAMILY_TEAM_ID)).willReturn(Optional.of(familyTeam));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(1L);
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(true);

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_DUPLICATED);
        }

        @Test
        @DisplayName("対象個人時間割が自分のものでなければ 404")
        void add_所有者NG_404() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
    }

    // ==================== remove ====================

    @Nested
    @DisplayName("remove")
    class RemoveTest {

        @Test
        @DisplayName("最後の1件を削除すると visibility が PRIVATE に戻る＋監査ログ2件")
        void remove_最後の1件_PRIVATEに戻る() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(sharedTimetable));
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(0L);

            service.remove(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID);

            ArgumentCaptor<PersonalTimetableEntity> ptCaptor =
                    ArgumentCaptor.forClass(PersonalTimetableEntity.class);
            verify(personalTimetableRepository, times(1)).save(ptCaptor.capture());
            assertThat(ptCaptor.getValue().getVisibility())
                    .isEqualTo(PersonalTimetableVisibility.PRIVATE);

            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.share_removed"), eq(USER_ID), isNull(),
                    eq(FAMILY_TEAM_ID), isNull(), isNull(), isNull(), isNull(), anyString());
            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.visibility_changed"), eq(USER_ID), isNull(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("残件があれば visibility は FAMILY_SHARED のまま、監査ログは share_removed のみ")
        void remove_残件あり_visibility不変() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(sharedTimetable));
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(true);
            given(shareTargetRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(2L);

            service.remove(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID);

            verify(personalTimetableRepository, never()).save(any());
            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.share_removed"), eq(USER_ID), isNull(),
                    eq(FAMILY_TEAM_ID), isNull(), isNull(), isNull(), isNull(), anyString());
            verify(auditLogService, never()).record(
                    eq("personal_timetable.visibility_changed"),
                    any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("対象が存在しなければ 404 (SHARE_TARGET_NOT_FOUND)")
        void remove_存在しない_404() {
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(sharedTimetable));
            given(shareTargetRepository.existsByPersonalTimetableIdAndTeamId(
                    TIMETABLE_ID, FAMILY_TEAM_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> service.remove(TIMETABLE_ID, USER_ID, FAMILY_TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(PersonalTimetableErrorCode.SHARE_TARGET_NOT_FOUND);
        }
    }

    // ==================== list ====================

    @Test
    @DisplayName("list: 所有者検証OK ならば repository へ委譲")
    void list_正常系() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(privateTimetable));
        PersonalTimetableShareTargetEntity ent = PersonalTimetableShareTargetEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .teamId(FAMILY_TEAM_ID)
                .build();
        given(shareTargetRepository.findByPersonalTimetableId(TIMETABLE_ID))
                .willReturn(List.of(ent));

        List<PersonalTimetableShareTargetEntity> result = service.list(TIMETABLE_ID, USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTeamId()).isEqualTo(FAMILY_TEAM_ID);
    }

    @Test
    @DisplayName("list: 所有者NG なら 404")
    void list_所有者NG_404() {
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                TIMETABLE_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(TIMETABLE_ID, USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
    }
}
