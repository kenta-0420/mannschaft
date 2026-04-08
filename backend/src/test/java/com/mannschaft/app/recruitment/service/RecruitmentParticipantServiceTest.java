package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * {@link RecruitmentParticipantService} の単体テスト。
 * §5.2 申込・§5.3 キャンセル・§9.10 acknowledged_fee 必須を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentParticipantService 単体テスト")
class RecruitmentParticipantServiceTest {

    @Mock
    private RecruitmentParticipantRepository participantRepository;

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentParticipantHistoryRepository historyRepository;

    @Mock
    private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    @Mock
    private RecruitmentCancellationPolicyService policyService;

    @Mock
    private RecruitmentListingService listingService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private RecruitmentMapper mapper;

    @InjectMocks
    private RecruitmentParticipantService service;

    private static final Long LISTING_ID = 200L;
    private static final Long USER_ID = 1L;

    // ========================================
    // §9.10 acknowledged_fee 必須
    // ========================================

    @Nested
    @DisplayName("cancelMyApplication - §9.10")
    class CancelAcknowledgement {

        @Test
        @DisplayName("acknowledged_fee=false → FEE_NOT_ACKNOWLEDGED (RECRUITMENT_304)")
        void cancel_notAcknowledged_throws304() {
            CancelMyApplicationRequest request = new CancelMyApplicationRequest(false, null);

            assertThatThrownBy(() -> service.cancelMyApplication(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.FEE_NOT_ACKNOWLEDGED);
        }

        @Test
        @DisplayName("request=null → FEE_NOT_ACKNOWLEDGED")
        void cancel_nullRequest_throws304() {
            assertThatThrownBy(() -> service.cancelMyApplication(LISTING_ID, USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.FEE_NOT_ACKNOWLEDGED);
        }

        @Test
        @DisplayName("fee_amount_at_request != 計算値 → CANCELLATION_FEE_MISMATCH (RECRUITMENT_308)")
        void cancel_feeMismatch_throws308() throws Exception {
            CancelMyApplicationRequest request = new CancelMyApplicationRequest(true, 1000); // 表示額 1000

            RecruitmentListingEntity listing = buildOpenListing();
            RecruitmentParticipantEntity participant = buildConfirmedParticipant();

            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(participantRepository.findActiveByListingAndUser(LISTING_ID, USER_ID))
                    .willReturn(Optional.of(participant));
            given(policyService.calculateFee(any(), any()))
                    .willReturn(new RecruitmentCancellationPolicyService.CalculatedFee(
                            null, null, null, null, 2500, false, 50.0)); // 実際は 2500

            assertThatThrownBy(() -> service.cancelMyApplication(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CANCELLATION_FEE_MISMATCH);
        }
    }

    // ========================================
    // §5.2 申込
    // ========================================

    @Nested
    @DisplayName("apply - §5.2")
    class Apply {

        @Test
        @DisplayName("DRAFT 状態 → DRAFT_NOT_APPLICABLE")
        void apply_draftStatus_throws() throws Exception {
            RecruitmentListingEntity listing = buildOpenListing();
            setField(listing, "status", RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            // 未払いチェック (空)
            lenient().when(cancellationRecordRepository.existsByUserIdAndPaymentStatusIn(
                    eq(USER_ID), any(Collection.class))).thenReturn(false);

            ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                    RecruitmentParticipantType.USER, null, null);

            assertThatThrownBy(() -> service.apply(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.DRAFT_NOT_APPLICABLE);
        }

        @Test
        @DisplayName("締切過ぎ → DEADLINE_EXCEEDED")
        void apply_deadlineExceeded_throws() throws Exception {
            RecruitmentListingEntity listing = buildOpenListing();
            setField(listing, "applicationDeadline", LocalDateTime.now().minusHours(1));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                    RecruitmentParticipantType.USER, null, null);

            assertThatThrownBy(() -> service.apply(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.DEADLINE_EXCEEDED);
        }

        @Test
        @DisplayName("INDIVIDUAL listing に TEAM 申込 → PARTICIPATION_TYPE_MISMATCH")
        void apply_typeMismatch_throws() throws Exception {
            RecruitmentListingEntity listing = buildOpenListing(); // INDIVIDUAL
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                    RecruitmentParticipantType.TEAM, 99L, null);

            assertThatThrownBy(() -> service.apply(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.PARTICIPATION_TYPE_MISMATCH);
        }

        @Test
        @DisplayName("未払いキャンセル料あり → CANCELLATION_PAYMENT_FAILED")
        void apply_unpaidCancellation_throws() throws Exception {
            RecruitmentListingEntity listing = buildOpenListing();
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(cancellationRecordRepository.existsByUserIdAndPaymentStatusIn(
                    eq(USER_ID), any(Collection.class))).willReturn(true);

            ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                    RecruitmentParticipantType.USER, null, null);

            assertThatThrownBy(() -> service.apply(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CANCELLATION_PAYMENT_FAILED);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentListingEntity buildOpenListing() throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(10L)
                .categoryId(100L)
                .title("test")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(1))
                .autoCancelAt(LocalDateTime.now().plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        return listing;
    }

    private RecruitmentParticipantEntity buildConfirmedParticipant() throws Exception {
        RecruitmentParticipantEntity p = RecruitmentParticipantEntity.builder()
                .listingId(LISTING_ID)
                .participantType(RecruitmentParticipantType.USER)
                .userId(USER_ID)
                .appliedBy(USER_ID)
                .status(RecruitmentParticipantStatus.CONFIRMED)
                .build();
        setField(p, "id", 999L);
        return p;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
