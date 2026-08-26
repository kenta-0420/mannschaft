package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.service.DailyAttendanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link DailyAttendanceController} の単体テスト。
 *
 * <p>DailyAttendanceController#getMyDailyAttendanceHistory の自己スコープ性を固定する契約テスト。
 * Controller は studentUserId・currentUserId のいずれにも常に
 * {@code SecurityUtils.getCurrentUserId()} を渡すため、他人の studentUserId を
 * 指定する余地が構造的に無い（Service 側も二重防御で本人チェックを行う）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DailyAttendanceController 単体テスト")
class DailyAttendanceControllerTest {

    @Mock
    private DailyAttendanceService dailyAttendanceService;

    @InjectMocks
    private DailyAttendanceController controller;

    private static final Long USER_ID = 100L;
    private static final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private static final LocalDate TO = LocalDate.of(2026, 4, 30);

    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUpSecurityUtils() {
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDownSecurityUtils() {
        securityUtils.close();
    }

    @Test
    @DisplayName("getMyDailyAttendanceHistory は SecurityUtils.getCurrentUserId() のみを本人特定に渡す")
    void getMyDailyAttendanceHistory_boundToCurrentUserOnly() {
        given(dailyAttendanceService.getStudentHistory(USER_ID, FROM, TO, USER_ID))
                .willReturn(List.of());

        controller.getMyDailyAttendanceHistory(FROM, TO);

        // 他人の studentUserId を渡す経路が存在しないことの裏取り。
        verify(dailyAttendanceService).getStudentHistory(USER_ID, FROM, TO, USER_ID);
    }
}
