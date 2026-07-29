package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.ChangeRequestType;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
    /** 認可根治 Wave7: create の slotId 帰属検証（BOLA 封鎖）で使用。 */
    @Mock
    private ShiftSlotRepository slotRepository;
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

    // ════════════════════════════════════════════════════════════
    // list — 権限昇格（?role=ADMIN）の封鎖
    //
    // 契約テスト（ShiftChangeRequestScopeContractIT）は Docker 前提で skip され得るため、
    // 認可の要（返却範囲の決定）は Docker 非依存の純 UT でも押さえる。
    // ════════════════════════════════════════════════════════════

    /** 一般メンバー（依頼者本人） */
    private static final Long MEMBER_ID = 21L;

    @Test
    @DisplayName("list: 一般メンバーには自分の依頼のみ返す（全件クエリは呼ばれない）")
    void list_一般メンバーは自分の分のみ() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(accessControlService.isSystemAdmin(MEMBER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(changeRequestRepository.findAllByRequestedByAndScheduleId(MEMBER_ID, SCHEDULE_ID))
                .willReturn(List.of());

        service.list(SCHEDULE_ID, MEMBER_ID);

        // 「自分の分だけ」のクエリのみが使われ、全件取得は行われない
        verify(changeRequestRepository).findAllByRequestedByAndScheduleId(MEMBER_ID, SCHEDULE_ID);
        verify(changeRequestRepository, never()).findAllByScheduleIdOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("list: 当該チーム ADMIN には全件返す")
    void list_ADMINは全件() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(REVIEWER_ID, TEAM_ID, "TEAM")).willReturn(true);
        given(changeRequestRepository.findAllByScheduleIdOrderByCreatedAtDesc(SCHEDULE_ID))
                .willReturn(List.of());

        service.list(SCHEDULE_ID, REVIEWER_ID);

        verify(changeRequestRepository).findAllByScheduleIdOrderByCreatedAtDesc(SCHEDULE_ID);
        verify(changeRequestRepository, never()).findAllByRequestedByAndScheduleId(any(), any());
    }

    @Test
    @DisplayName("list: 非メンバーは COMMON_002（一覧そのものを拒否）")
    void list_非メンバーはCOMMON_002() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(REVIEWER_ID, TEAM_ID, "TEAM")).willReturn(false);
        given(accessControlService.isMember(REVIEWER_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> service.list(SCHEDULE_ID, REVIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

        verify(changeRequestRepository, never()).findAllByScheduleIdOrderByCreatedAtDesc(any());
        verify(changeRequestRepository, never()).findAllByRequestedByAndScheduleId(any(), any());
    }

    // ════════════════════════════════════════════════════════════
    // get — 死文だった IDOR チェックの実装
    // ════════════════════════════════════════════════════════════

    @Test
    @DisplayName("get: 依頼者本人は取得できる（scope 解決すら不要）")
    void get_依頼者本人はOK() {
        given(changeRequestRepository.findById(REQUEST_ID))
                .willReturn(Optional.of(requestedBy(MEMBER_ID)));

        service.get(REQUEST_ID, MEMBER_ID);

        // 本人一致で通るため schedule 解決は走らない
        verify(scheduleRepository, never()).findById(any());
    }

    @Test
    @DisplayName("get: 当該チーム ADMIN は他人の依頼も取得できる")
    void get_チームADMINはOK() {
        given(changeRequestRepository.findById(REQUEST_ID))
                .willReturn(Optional.of(requestedBy(MEMBER_ID)));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(REVIEWER_ID, TEAM_ID, "TEAM")).willReturn(true);

        service.get(REQUEST_ID, REVIEWER_ID);
    }

    @Test
    @DisplayName("get: 他人かつ非 ADMIN は 404 相当（CHANGE_REQUEST_NOT_FOUND / 存在秘匿）")
    void get_他人かつ非ADMINは存在秘匿() {
        given(changeRequestRepository.findById(REQUEST_ID))
                .willReturn(Optional.of(requestedBy(MEMBER_ID)));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(accessControlService.isSystemAdmin(REVIEWER_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(REVIEWER_ID, TEAM_ID, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> service.get(REQUEST_ID, REVIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND));
    }

    // ════════════════════════════════════════════════════════════
    // create — 認可根治 Wave7（非メンバーによる他チームへの依頼投入の封鎖）
    // ════════════════════════════════════════════════════════════

    private static final Long SLOT_ID = 800L;

    private CreateChangeRequestRequest createReq(Long slotId) {
        return new CreateChangeRequestRequest(SCHEDULE_ID, slotId, ChangeRequestType.OPEN_CALL, "理由");
    }

    @Test
    @DisplayName("create: 非メンバーは COMMON_002（メンバーシップ検証で拒否）")
    void create_非メンバーはCOMMON_002() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(REVIEWER_ID, TEAM_ID, "TEAM");

        assertThatThrownBy(() -> service.create(createReq(null), REVIEWER_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CommonErrorCode.COMMON_002.getMessage());

        verify(changeRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: 別スケジュールの slotId を指定すると SHIFT_SLOT_NOT_FOUND（BOLA・存在秘匿）")
    void create_別スケジュールのslotは404() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(
                ShiftSlotEntity.builder().scheduleId(999L).build()));

        assertThatThrownBy(() -> service.create(createReq(SLOT_ID), MEMBER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));

        verify(changeRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: メンバー＋自スケジュールの slot なら作成できる（機能非回帰）")
    void create_メンバーは作成できる() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                ShiftScheduleEntity.builder().teamId(TEAM_ID).build()));
        given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(
                ShiftSlotEntity.builder().scheduleId(SCHEDULE_ID).build()));
        given(changeRequestRepository.countByRequestedByAndRequestTypeInCurrentMonth(
                MEMBER_ID, ChangeRequestType.OPEN_CALL)).willReturn(0L);
        given(changeRequestRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.create(createReq(SLOT_ID), MEMBER_ID);

        verify(changeRequestRepository).save(any());
    }

    /** 指定ユーザーが依頼者である変更依頼を作る */
    private ShiftChangeRequestEntity requestedBy(Long requesterId) {
        return ShiftChangeRequestEntity.builder()
                .id(REQUEST_ID)
                .scheduleId(SCHEDULE_ID)
                .status(ChangeRequestStatus.OPEN)
                .requestedBy(requesterId)
                .version(0L)
                .build();
    }
}
