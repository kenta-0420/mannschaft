package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftChangeRequestService#review} の per-scope 認可（認可根治 Phase 3-a / 生穴封鎖）単体テスト。
 *
 * <p>本メソッドはかつて認可がなく「認証済みなら誰でも任意のシフト変更依頼を審査（承認/却下）できる」
 * 生穴であった。本テストは {@code scheduleId → teamId} 解決による IDOR 封鎖込みの per-scope 認可
 *（SYSTEM_ADMIN 短絡 or 当該チーム ADMIN/DEPUTY_ADMIN）が効くことを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftChangeRequestService.review 認可（生穴封鎖）単体テスト")
class ShiftChangeRequestServiceAuthzTest {

    @Mock
    private ShiftChangeRequestRepository changeRequestRepository;
    @Mock
    private ShiftScheduleRepository scheduleRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftChangeRequestService service;

    private static final Long REQUEST_ID = 500L;
    private static final Long SCHEDULE_ID = 70L;
    private static final Long TEAM_ID = 10L;
    private static final Long REVIEWER_ID = 9L;

    private ShiftChangeRequestEntity openRequest() {
        return ShiftChangeRequestEntity.builder()
                .id(REQUEST_ID)
                .scheduleId(SCHEDULE_ID)
                .status(ChangeRequestStatus.OPEN)
                .version(0L)
                .build();
    }

    private ReviewChangeRequestRequest reviewReq() {
        return new ReviewChangeRequestRequest(ChangeRequestStatus.ACCEPTED, "承認", 0);
    }

    @Test
    @DisplayName("非権限者（他団体含む）は COMMON_002（scheduleId→teamId 解決後に弾く）")
    void review_非権限者_COMMON_002() {
        given(changeRequestRepository.findById(REQUEST_ID)).willReturn(Optional.of(openRequest()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(false);
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(REVIEWER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> service.review(REQUEST_ID, reviewReq(), REVIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

        // 認可で弾かれたので保存（審査確定）は行われない
        verify(changeRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は scheduleId 解決なしで認可通過（短絡）")
    void review_SYSTEM_ADMIN_短絡() {
        given(changeRequestRepository.findById(REQUEST_ID)).willReturn(Optional.of(openRequest()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(true);
        given(changeRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        // 例外なく審査が確定する
        service.review(REQUEST_ID, reviewReq(), REVIEWER_ID);

        // SYSTEM_ADMIN 短絡したので schedule 解決 / per-scope 判定は呼ばれない
        verify(scheduleRepository, never()).findById(any());
        verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
        verify(changeRequestRepository).save(any());
    }
}
