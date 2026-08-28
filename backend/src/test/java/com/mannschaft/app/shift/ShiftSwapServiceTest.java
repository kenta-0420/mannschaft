package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftSwapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSwapService} の単体テスト。
 * シフト交代リクエストの作成・承諾・承認・却下・キャンセルを検証する。
 *
 * <p>認可根治 Wave6 で全 public メソッドが per-scope 認可を行うようになったため、
 * 交代申請 → シフト枠 → スケジュール → teamId の解決経路と
 * {@link AccessControlService} の判定をモックで満たしたうえで業務ロジックを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSwapService 単体テスト")
class ShiftSwapServiceTest {

    @Mock
    private ShiftSwapRequestRepository swapRepository;

    @Mock
    private ShiftSlotRepository slotRepository;

    @Mock
    private ShiftScheduleRepository scheduleRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ShiftMapper shiftMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private ShiftSwapService shiftSwapService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SWAP_ID = 400L;
    private static final Long SLOT_ID = 200L;
    private static final Long SCHEDULE_ID = 300L;
    private static final Long TEAM_ID = 100L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long ACCEPTER_ID = 20L;
    private static final Long ADMIN_ID = 30L;

    /** slotId → scheduleId → teamId の scope 解決経路をモックする。 */
    private void givenScopeResolvable() {
        given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(ShiftSlotEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .slotDate(LocalDate.of(2026, 3, 1))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(18, 0))
                .build()));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("テストシフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.DRAFT)
                .build()));
    }

    /** 当該ユーザーを「当該チームの一般メンバー（SUPPORTER でない）」として認可を通す。 */
    private void givenTeamMember(Long userId) {
        givenScopeResolvable();
        given(accessControlService.isSystemAdmin(userId)).willReturn(false);
        given(accessControlService.isMember(userId, TEAM_ID, "TEAM")).willReturn(true);
        given(accessControlService.isSupporter(userId, TEAM_ID, "TEAM")).willReturn(false);
    }

    /** 当該ユーザーを「当該チームの ADMIN」として認可を通す（checkAdminOrAbove は void で何もしない）。 */
    private void givenTeamAdmin(Long userId) {
        givenScopeResolvable();
        given(accessControlService.isSystemAdmin(userId)).willReturn(false);
    }

    private ShiftSwapRequestEntity createPendingSwap() {
        ShiftSwapRequestEntity entity = ShiftSwapRequestEntity.builder()
                .slotId(SLOT_ID)
                .requesterId(REQUESTER_ID)
                .status(SwapRequestStatus.PENDING)
                .reason("体調不良のため")
                .build();
        callOnCreate(entity);
        return entity;
    }

    private ShiftSwapRequestEntity createAcceptedSwap() {
        ShiftSwapRequestEntity entity = createPendingSwap();
        entity.accept(ACCEPTER_ID);
        return entity;
    }

    private SwapRequestResponse createSwapResponse() {
        return SwapRequestResponse.builder()
                .id(SWAP_ID)
                .slotId(SLOT_ID)
                .requesterId(REQUESTER_ID)
                .status("PENDING")
                .reason("体調不良のため")
                .createdAt(LocalDateTime.now())
                .recipientMode("SPECIFIC")
                .build();
    }

    private void callOnCreate(Object entity) {
        try {
            Method method = entity.getClass().getSuperclass().getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    // ========================================
    // listSwapRequests
    // ========================================

    @Nested
    @DisplayName("listSwapRequests")
    class ListSwapRequests {

        @Test
        @DisplayName("交代リクエスト一覧_ステータス指定_当該チームのフィルタ結果返却")
        void 交代リクエスト一覧_ステータス指定_当該チームのフィルタ結果返却() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(false);
            given(swapRepository.findByTeamIdAndStatusOrderByCreatedAtAsc(TEAM_ID, SwapRequestStatus.PENDING))
                    .willReturn(List.of(entity));
            given(shiftMapper.toSwapResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SwapRequestResponse> result = shiftSwapService.listSwapRequests(TEAM_ID, "PENDING", ADMIN_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("交代リクエスト一覧_ステータス未指定_当該チーム全件返却（全テナント横断しない）")
        void 交代リクエスト一覧_ステータス未指定_当該チーム全件返却() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(false);
            given(swapRepository.findByTeamIdOrderByCreatedAtAsc(TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toSwapResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SwapRequestResponse> result = shiftSwapService.listSwapRequests(TEAM_ID, null, ADMIN_ID);

            // Then
            assertThat(result).hasSize(1);
            // team スコープの引きのみを使い、全件取得（findAll）へは決して落ちないこと
            verify(swapRepository).findByTeamIdOrderByCreatedAtAsc(TEAM_ID);
            verify(swapRepository, org.mockito.Mockito.never())
                    .findAll(any(org.springframework.data.domain.Pageable.class));
        }

        @Test
        @DisplayName("交代リクエスト一覧_当該チームのADMINでない_BusinessException")
        void 交代リクエスト一覧_当該チームのADMINでない_BusinessException() {
            // Given
            given(accessControlService.isSystemAdmin(ACCEPTER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(ACCEPTER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.listSwapRequests(TEAM_ID, null, ACCEPTER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // listMySwapRequests
    // ========================================

    @Nested
    @DisplayName("listMySwapRequests")
    class ListMySwapRequests {

        @Test
        @DisplayName("自分の交代リクエスト一覧取得_正常_リスト返却")
        void 自分の交代リクエスト一覧取得_正常_リスト返却() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findByRequesterIdOrderByCreatedAtDesc(REQUESTER_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toSwapResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SwapRequestResponse> result = shiftSwapService.listMySwapRequests(REQUESTER_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createSwapRequest
    // ========================================

    @Nested
    @DisplayName("createSwapRequest")
    class CreateSwapRequest {

        @Test
        @DisplayName("交代リクエスト作成_正常_レスポンス返却")
        void 交代リクエスト作成_正常_レスポンス返却() {
            // Given
            CreateSwapRequestRequest req = new CreateSwapRequestRequest(SLOT_ID, "体調不良のため", false, null);
            ShiftSwapRequestEntity savedEntity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            givenTeamMember(REQUESTER_ID);
            given(swapRepository.save(any(ShiftSwapRequestEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toSwapResponse(savedEntity)).willReturn(response);

            // When
            SwapRequestResponse result = shiftSwapService.createSwapRequest(req, REQUESTER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(swapRepository).save(any(ShiftSwapRequestEntity.class));
        }

        @Test
        @DisplayName("交代リクエスト作成_OPEN_CALL_recipientModeがOPEN_CALL")
        void 交代リクエスト作成_OPEN_CALL_recipientModeがOPEN_CALL() {
            // Given
            CreateSwapRequestRequest req = new CreateSwapRequestRequest(SLOT_ID, "急な用事のため", true, null);
            ShiftSwapRequestEntity savedEntity = ShiftSwapRequestEntity.builder()
                    .slotId(SLOT_ID).requesterId(REQUESTER_ID)
                    .status(SwapRequestStatus.PENDING).isOpenCall(true)
                    .recipientMode("OPEN_CALL").build();
            SwapRequestResponse response = createSwapResponse();
            givenTeamMember(REQUESTER_ID);
            given(swapRepository.save(any(ShiftSwapRequestEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toSwapResponse(savedEntity)).willReturn(response);

            // When
            SwapRequestResponse result = shiftSwapService.createSwapRequest(req, REQUESTER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(swapRepository).save(any(ShiftSwapRequestEntity.class));
        }

        @Test
        @DisplayName("交代リクエスト作成_SPECIFIC_targetUserIds指定あり")
        void 交代リクエスト作成_SPECIFIC_targetUserIds指定あり() {
            // Given
            CreateSwapRequestRequest req = new CreateSwapRequestRequest(SLOT_ID, "理由", false, List.of(20L, 30L));
            ShiftSwapRequestEntity savedEntity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            givenTeamMember(REQUESTER_ID);
            given(swapRepository.save(any(ShiftSwapRequestEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toSwapResponse(savedEntity)).willReturn(response);

            // When
            SwapRequestResponse result = shiftSwapService.createSwapRequest(req, REQUESTER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(swapRepository).save(any(ShiftSwapRequestEntity.class));
        }
    }

    // ========================================
    // acceptSwapRequest
    // ========================================

    @Nested
    @DisplayName("acceptSwapRequest")
    class AcceptSwapRequest {

        @Test
        @DisplayName("交代リクエスト承諾_正常_ステータスACCEPTED")
        void 交代リクエスト承諾_正常_ステータスACCEPTED() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamMember(ACCEPTER_ID);
            given(swapRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toSwapResponse(entity)).willReturn(response);

            // When
            shiftSwapService.acceptSwapRequest(SWAP_ID, ACCEPTER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.ACCEPTED);
            assertThat(entity.getAccepterId()).isEqualTo(ACCEPTER_ID);
        }

        @Test
        @DisplayName("交代リクエスト承諾_自分自身_BusinessException")
        void 交代リクエスト承諾_自分自身_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamMember(REQUESTER_ID);

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.acceptSwapRequest(SWAP_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SWAP_SELF_REQUEST));
        }

        @Test
        @DisplayName("交代リクエスト承諾_PENDING以外_BusinessException")
        void 交代リクエスト承諾_PENDING以外_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamMember(ACCEPTER_ID);

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.acceptSwapRequest(SWAP_ID, ACCEPTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SWAP_STATUS));
        }

        @Test
        @DisplayName("交代リクエスト承諾_存在しない_BusinessException")
        void 交代リクエスト承諾_存在しない_BusinessException() {
            // Given
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.acceptSwapRequest(SWAP_ID, ACCEPTER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // resolveSwapRequest
    // ========================================

    @Nested
    @DisplayName("resolveSwapRequest")
    class ResolveSwapRequest {

        @Test
        @DisplayName("交代リクエスト承認_APPROVE_正常")
        void 交代リクエスト承認_APPROVE_正常() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            ResolveSwapRequestRequest req = new ResolveSwapRequestRequest("APPROVE", "承認します");
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamAdmin(ADMIN_ID);
            given(swapRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toSwapResponse(entity)).willReturn(response);

            // When
            shiftSwapService.resolveSwapRequest(SWAP_ID, req, ADMIN_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.APPROVED);
            assertThat(entity.getResolvedBy()).isEqualTo(ADMIN_ID);
            assertThat(entity.getAdminNote()).isEqualTo("承認します");
        }

        @Test
        @DisplayName("交代リクエスト却下_REJECT_正常")
        void 交代リクエスト却下_REJECT_正常() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            ResolveSwapRequestRequest req = new ResolveSwapRequestRequest("REJECT", "却下理由");
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamAdmin(ADMIN_ID);
            given(swapRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toSwapResponse(entity)).willReturn(response);

            // When
            shiftSwapService.resolveSwapRequest(SWAP_ID, req, ADMIN_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.REJECTED);
        }

        @Test
        @DisplayName("交代リクエスト処理_ACCEPTED以外_BusinessException")
        void 交代リクエスト処理_ACCEPTED以外_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            ResolveSwapRequestRequest req = new ResolveSwapRequestRequest("APPROVE", null);
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamAdmin(ADMIN_ID);

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.resolveSwapRequest(SWAP_ID, req, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SWAP_STATUS));
        }

        @Test
        @DisplayName("交代リクエスト処理_不正アクション_BusinessException")
        void 交代リクエスト処理_不正アクション_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            ResolveSwapRequestRequest req = new ResolveSwapRequestRequest("INVALID_ACTION", null);
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenTeamAdmin(ADMIN_ID);

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.resolveSwapRequest(SWAP_ID, req, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SWAP_STATUS));
        }

        @Test
        @DisplayName("交代リクエスト承認_当該チームのADMINでない_BusinessExceptionでステータス不変")
        void 交代リクエスト承認_当該チームのADMINでない_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            ResolveSwapRequestRequest req = new ResolveSwapRequestRequest("APPROVE", "承認します");
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenScopeResolvable();
            given(accessControlService.isSystemAdmin(ACCEPTER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(ACCEPTER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.resolveSwapRequest(SWAP_ID, req, ACCEPTER_ID))
                    .isInstanceOf(BusinessException.class);
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.ACCEPTED);
        }
    }

    // ========================================
    // cancelSwapRequest
    // ========================================

    @Nested
    @DisplayName("cancelSwapRequest")
    class CancelSwapRequest {

        @Test
        @DisplayName("交代リクエストキャンセル_正常_ステータスCANCELLED")
        void 交代リクエストキャンセル_正常_ステータスCANCELLED() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            given(swapRepository.save(entity)).willReturn(entity);

            // When
            shiftSwapService.cancelSwapRequest(SWAP_ID, REQUESTER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.CANCELLED);
            verify(swapRepository).save(entity);
        }

        @Test
        @DisplayName("交代リクエストキャンセル_PENDING以外_BusinessException")
        void 交代リクエストキャンセル_PENDING以外_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createAcceptedSwap();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.cancelSwapRequest(SWAP_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SWAP_STATUS));
        }

        @Test
        @DisplayName("交代リクエストキャンセル_存在しない_BusinessException")
        void 交代リクエストキャンセル_存在しない_BusinessException() {
            // Given
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.cancelSwapRequest(SWAP_ID, REQUESTER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("交代リクエストキャンセル_申請者でもADMINでもない_BusinessExceptionでステータス不変")
        void 交代リクエストキャンセル_申請者でもADMINでもない_BusinessException() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            given(swapRepository.findById(SWAP_ID)).willReturn(Optional.of(entity));
            givenScopeResolvable();
            given(accessControlService.isSystemAdmin(ACCEPTER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(
                            new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(ACCEPTER_ID, TEAM_ID, "TEAM");

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.cancelSwapRequest(SWAP_ID, ACCEPTER_ID))
                    .isInstanceOf(BusinessException.class);
            assertThat(entity.getStatus()).isEqualTo(SwapRequestStatus.PENDING);
        }
    }
}
