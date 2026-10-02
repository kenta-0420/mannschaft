package com.mannschaft.app.school.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.AttendancePermissionsResponse;
import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import com.mannschaft.app.school.repository.ClassHomeroomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchoolAttendanceAccessPolicy 単体テスト（純粋な判定ロジック）")
class SchoolAttendanceAccessPolicyTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final Long TEAM = 10L;
    private static final Long MAIN = 1L;
    private static final Long ASSISTANT = 2L;
    private static final Long OTHER = 3L;

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ClassHomeroomRepository classHomeroomRepository;

    private SchoolAttendanceAccessPolicy policy;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(TODAY.atTime(0, 30).atZone(JST).toInstant(), JST);
        policy = new SchoolAttendanceAccessPolicy(
                accessControlService, classHomeroomRepository, new ObjectMapper(), clock);
        when(classHomeroomRepository.findActiveByTeamId(anyLong(), any())).thenReturn(List.of());
        // 既定では全員がチームの有効なメンバー。退会者は個別テストで false にする。
        when(accessControlService.isMember(anyLong(), anyLong(), eq("TEAM"))).thenReturn(true);
    }

    private static ClassHomeroomEntity row(Long main, String assistants) {
        return ClassHomeroomEntity.builder()
                .teamId(TEAM)
                .homeroomTeacherUserId(main)
                .assistantTeacherUserIds(assistants)
                .academicYear(2026)
                .effectiveFrom(TODAY.minusDays(30))
                .createdBy(99L)
                .build();
    }

    private void activeRows(ClassHomeroomEntity... rows) {
        when(classHomeroomRepository.findActiveByTeamId(TEAM, TODAY)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("業務ゾーン(Asia/Tokyo)の今日で現役行を引く（UTC では前日になる時刻でも JST の日付）")
    void 今日は業務ゾーンの日付() {
        activeRows(row(MAIN, null));

        assertThat(policy.isActiveHomeroomTeacher(MAIN, TEAM)).isTrue();
    }

    @Test
    @DisplayName("担任・副担任は V/R/P、無関係者は全て false")
    void 担任と副担任は許可() {
        activeRows(row(MAIN, "[" + ASSISTANT + "]"));

        for (Long u : new Long[]{MAIN, ASSISTANT}) {
            assertThat(policy.canView(u, TEAM)).isTrue();
            assertThat(policy.canRecordDaily(u, TEAM)).isTrue();
            assertThat(policy.canRecordPeriod(u, TEAM)).isTrue();
        }
        assertThat(policy.canView(OTHER, TEAM)).isFalse();
        assertThat(policy.canRecordDaily(OTHER, TEAM)).isFalse();
        assertThat(policy.canRecordPeriod(OTHER, TEAM)).isFalse();
    }

    @Test
    @DisplayName("現役行が複数なら全行の担任・副担任の和集合")
    void 複数行は和集合() {
        activeRows(row(MAIN, null), row(OTHER, "[" + ASSISTANT + "]"));

        assertThat(policy.canRecordDaily(MAIN, TEAM)).isTrue();
        assertThat(policy.canRecordDaily(OTHER, TEAM)).isTrue();
        assertThat(policy.canRecordDaily(ASSISTANT, TEAM)).isTrue();
    }

    @Test
    @DisplayName("assistant JSON が不正なら副担任資格のみ無効で、主担任は有効・例外なし")
    void 不正JSONは副担任のみ無効() {
        for (String bad : new String[]{"[]", "{\"x\":1}", "\"abc\"", "[null]", "[\"abc\"]", "[1.5]",
                "[99999999999999999999]", "null", "{broken", "[" + ASSISTANT + ", \"x\"]"}) {
            activeRows(row(MAIN, bad));

            assertThat(policy.canRecordDaily(MAIN, TEAM)).as(bad).isTrue();
            assertThat(policy.canRecordDaily(ASSISTANT, TEAM)).as(bad).isFalse();
        }
    }

    @Test
    @DisplayName("ADMIN/DEPUTY_ADMIN は V/R/P。VIEW_ATTENDANCE 委任者は V のみ")
    void 管理者と委任者() {
        when(accessControlService.isAdminOrAbove(OTHER, TEAM, "TEAM")).thenReturn(true);
        assertThat(policy.canView(OTHER, TEAM)).isTrue();
        assertThat(policy.canRecordDaily(OTHER, TEAM)).isTrue();
        assertThat(policy.canRecordPeriod(OTHER, TEAM)).isTrue();

        when(accessControlService.hasPermission(ASSISTANT, TEAM, "TEAM", "VIEW_ATTENDANCE")).thenReturn(true);
        assertThat(policy.canView(ASSISTANT, TEAM)).isTrue();
        assertThat(policy.canRecordDaily(ASSISTANT, TEAM)).isFalse();
        assertThat(policy.canRecordPeriod(ASSISTANT, TEAM)).isFalse();
    }

    @Test
    @DisplayName("チームを離れた担任・副担任は、名簿の行が残っていても V/R/P の資格を持たない")
    void 退会した担任は資格なし() {
        activeRows(row(MAIN, "[" + ASSISTANT + "]"));
        when(accessControlService.isMember(MAIN, TEAM, "TEAM")).thenReturn(false);
        when(accessControlService.isMember(ASSISTANT, TEAM, "TEAM")).thenReturn(false);

        for (Long u : new Long[]{MAIN, ASSISTANT}) {
            assertThat(policy.isActiveHomeroomTeacher(u, TEAM)).isFalse();
            assertThat(policy.canView(u, TEAM)).isFalse();
            assertThat(policy.canRecordDaily(u, TEAM)).isFalse();
            assertThat(policy.canRecordPeriod(u, TEAM)).isFalse();
        }
    }

    @Test
    @DisplayName("requireEnrolledStudents: 全員在籍なら通り、1 人でも非在籍なら STUDENT_NOT_ENROLLED")
    void 在籍検証() {
        when(accessControlService.listActiveMemberIds(TEAM, "TEAM")).thenReturn(List.of(MAIN, ASSISTANT));

        policy.requireEnrolledStudents(TEAM, List.of(MAIN, ASSISTANT));
        assertThatThrownBy(() -> policy.requireEnrolledStudents(TEAM, List.of(MAIN, OTHER)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(
                                com.mannschaft.app.school.error.SchoolErrorCode.STUDENT_NOT_ENROLLED));
    }

    @Test
    @DisplayName("userId / teamId が null なら false（例外にしない）")
    void nullは拒否() {
        assertThat(policy.canView(null, TEAM)).isFalse();
        assertThat(policy.canRecordDaily(MAIN, null)).isFalse();
    }

    @Test
    @DisplayName("check 系は権限なしで 403 COMMON_002 の BusinessException")
    void check系は例外() {
        assertThatThrownBy(() -> policy.checkCanView(OTHER, TEAM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.checkCanRecordDaily(OTHER, TEAM)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.checkCanRecordPeriod(OTHER, TEAM)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("resolvePermissions は権限なしでも例外にせず全 false")
    void 判定結果は全false() {
        AttendancePermissionsResponse r = policy.resolvePermissions(OTHER, TEAM);

        assertThat(r.teamId()).isEqualTo(TEAM);
        assertThat(r.canView()).isFalse();
        assertThat(r.canRecordDaily()).isFalse();
        assertThat(r.canRecordPeriod()).isFalse();
    }
}
