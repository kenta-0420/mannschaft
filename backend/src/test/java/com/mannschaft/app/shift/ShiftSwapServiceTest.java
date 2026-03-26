package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.CreateSwapRequestRequest;
import com.mannschaft.app.shift.dto.ResolveSwapRequestRequest;
import com.mannschaft.app.shift.dto.SwapRequestResponse;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftSwapService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
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
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSwapService 単体テスト")
class ShiftSwapServiceTest {

    @Mock
    private ShiftSwapRequestRepository swapRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @InjectMocks
    private ShiftSwapService shiftSwapService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SWAP_ID = 400L;
    private static final Long SLOT_ID = 200L;
    private static final Long REQUESTER_ID = 10L;
    private static final Long ACCEPTER_ID = 20L;
    private static final Long ADMIN_ID = 30L;

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
        return new SwapRequestResponse(
                SWAP_ID, SLOT_ID, REQUESTER_ID, null,
                "PENDING", "体調不良のため", null, null, null,
                LocalDateTime.now());
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
        @DisplayName("交代リクエスト一覧_ステータス指定_フィルタ結果返却")
        void 交代リクエスト一覧_ステータス指定_フィルタ結果返却() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findByStatusOrderByCreatedAtAsc(SwapRequestStatus.PENDING))
                    .willReturn(List.of(entity));
            given(shiftMapper.toSwapResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SwapRequestResponse> result = shiftSwapService.listSwapRequests("PENDING");

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("交代リクエスト一覧_ステータス未指定_全件返却")
        void 交代リクエスト一覧_ステータス未指定_全件返却() {
            // Given
            ShiftSwapRequestEntity entity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
            given(swapRepository.findAll()).willReturn(List.of(entity));
            given(shiftMapper.toSwapResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<SwapRequestResponse> result = shiftSwapService.listSwapRequests(null);

            // Then
            assertThat(result).hasSize(1);
            verify(swapRepository).findAll();
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
            CreateSwapRequestRequest req = new CreateSwapRequestRequest(SLOT_ID, "体調不良のため");
            ShiftSwapRequestEntity savedEntity = createPendingSwap();
            SwapRequestResponse response = createSwapResponse();
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

            // When & Then
            assertThatThrownBy(() -> shiftSwapService.resolveSwapRequest(SWAP_ID, req, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SWAP_STATUS));
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
    }
}
