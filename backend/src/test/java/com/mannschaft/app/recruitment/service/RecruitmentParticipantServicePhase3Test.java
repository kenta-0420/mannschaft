package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentParticipantService} Phase 3 機能の単体テスト。
 * §5.3 キャンセル待ち自動昇格 (promoteFromWaitlistIfPossible) を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentParticipantService Phase3 単体テスト")
class RecruitmentParticipantServicePhase3Test {

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
    private static final Long WAITLIST_USER_ID = 2L;

    // ========================================
    // §5.3 キャンセル待ち昇格
    // ========================================

    @Nested
    @DisplayName("cancelMyApplication - §5.3 キャンセル待ち自動昇格")
    class WaitlistPromotion {

        @Test
        @DisplayName("CONFIRMED がキャンセル → waitlisted 1件を昇格する")
        void cancelMyApplication_confirmedWithWaitlisted_promotesFirst() throws Exception {
            // given
            CancelMyApplicationRequest request = new CancelMyApplicationRequest(true, null);

            // キャンセル対象の listing (空きが出るように confirmed=1, capacity=10)
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 1);
            // キャンセル後の再ロード用 listing (confirmed が 0 になり空きあり)
            RecruitmentListingEntity reloadedForPromotion = buildOpenListing(10, 0, 1);

            // キャンセル対象の CONFIRMED 参加者
            RecruitmentParticipantEntity confirmed = buildParticipant(USER_ID, RecruitmentParticipantStatus.CONFIRMED);
            // キャンセル待ちの参加者
            RecruitmentParticipantEntity waitlisted = buildParticipant(WAITLIST_USER_ID, RecruitmentParticipantStatus.WAITLISTED);
            setField(waitlisted, "waitlistPosition", 1);

            // findByIdForUpdate の連続呼び出しをシミュレート
            // 1回目: キャンセル処理用, 2回目: waitlist decrementWaitlist 用, 3回目: promotion 用
            given(listingRepository.findByIdForUpdate(LISTING_ID))
                    .willReturn(Optional.of(listing))       // 1回目（最初のロック）
                    .willReturn(Optional.of(listing))       // 2回目（waitlist decrementWaitlist は wasWaitlisted=false なので呼ばれない）
                    .willReturn(Optional.of(reloadedForPromotion)); // 3回目（昇格チェック用）

            given(participantRepository.findActiveByListingAndUser(LISTING_ID, USER_ID))
                    .willReturn(Optional.of(confirmed));
            given(policyService.calculateFee(any(), any()))
                    .willReturn(new RecruitmentCancellationPolicyService.CalculatedFee(
                            null, null, null, null, 0, false, 0.0));
            given(participantRepository.save(any())).willReturn(confirmed);
            given(cancellationRecordRepository.save(any())).willReturn(null);

            // キャンセル待ち先頭を返す
            given(participantRepository.findFirstWaitlistedForUpdate(LISTING_ID))
                    .willReturn(Optional.of(waitlisted));

            RecruitmentParticipantResponse mockResponse = new RecruitmentParticipantResponse(
                    999L, LISTING_ID, "USER", USER_ID, null, USER_ID,
                    "CANCELLED", null, null, null, null);
            given(mapper.toParticipantResponse(any())).willReturn(mockResponse);

            // when
            service.cancelMyApplication(LISTING_ID, USER_ID, request);

            // then: findFirstWaitlistedForUpdate が呼ばれ、waitlisted 参加者が save された
            verify(participantRepository).findFirstWaitlistedForUpdate(LISTING_ID);
            verify(participantRepository).save(waitlisted);
        }

        @Test
        @DisplayName("CONFIRMED がキャンセル → waitlist なし → 昇格なし")
        void cancelMyApplication_confirmedWithNoWaitlisted_noPromotion() throws Exception {
            // given
            CancelMyApplicationRequest request = new CancelMyApplicationRequest(true, null);

            // 空きあり listing
            RecruitmentListingEntity listing = buildOpenListing(10, 1, 1);
            RecruitmentListingEntity reloadedForPromotion = buildOpenListing(10, 0, 1);

            RecruitmentParticipantEntity confirmed = buildParticipant(USER_ID, RecruitmentParticipantStatus.CONFIRMED);

            given(listingRepository.findByIdForUpdate(LISTING_ID))
                    .willReturn(Optional.of(listing))
                    .willReturn(Optional.of(reloadedForPromotion));

            given(participantRepository.findActiveByListingAndUser(LISTING_ID, USER_ID))
                    .willReturn(Optional.of(confirmed));
            given(policyService.calculateFee(any(), any()))
                    .willReturn(new RecruitmentCancellationPolicyService.CalculatedFee(
                            null, null, null, null, 0, false, 0.0));
            given(participantRepository.save(any())).willReturn(confirmed);
            given(cancellationRecordRepository.save(any())).willReturn(null);

            // キャンセル待ちなし
            given(participantRepository.findFirstWaitlistedForUpdate(LISTING_ID))
                    .willReturn(Optional.empty());

            RecruitmentParticipantResponse mockResponse = new RecruitmentParticipantResponse(
                    999L, LISTING_ID, "USER", USER_ID, null, USER_ID,
                    "CANCELLED", null, null, null, null);
            given(mapper.toParticipantResponse(any())).willReturn(mockResponse);

            // when
            service.cancelMyApplication(LISTING_ID, USER_ID, request);

            // then: findFirstWaitlistedForUpdate は呼ばれるが、waitlisted 参加者はいないので save 1回のみ
            verify(participantRepository).findFirstWaitlistedForUpdate(LISTING_ID);
        }

        @Test
        @DisplayName("APPLIED がキャンセル → 昇格なし（CONFIRMED でないため）")
        void cancelMyApplication_appliedParticipant_noPromotion() throws Exception {
            // given
            CancelMyApplicationRequest request = new CancelMyApplicationRequest(true, null);

            // APPLIED 参加者がキャンセル → wasConfirmed=false のため promotion 呼ばれない
            RecruitmentListingEntity listing = buildOpenListing(10, 0, 1);
            RecruitmentParticipantEntity applied = buildParticipant(USER_ID, RecruitmentParticipantStatus.APPLIED);

            given(listingRepository.findByIdForUpdate(LISTING_ID))
                    .willReturn(Optional.of(listing));
            given(participantRepository.findActiveByListingAndUser(LISTING_ID, USER_ID))
                    .willReturn(Optional.of(applied));
            given(policyService.calculateFee(any(), any()))
                    .willReturn(new RecruitmentCancellationPolicyService.CalculatedFee(
                            null, null, null, null, 0, false, 0.0));
            given(participantRepository.save(any())).willReturn(applied);
            given(cancellationRecordRepository.save(any())).willReturn(null);

            RecruitmentParticipantResponse mockResponse = new RecruitmentParticipantResponse(
                    999L, LISTING_ID, "USER", USER_ID, null, USER_ID,
                    "CANCELLED", null, null, null, null);
            given(mapper.toParticipantResponse(any())).willReturn(mockResponse);

            // when
            service.cancelMyApplication(LISTING_ID, USER_ID, request);

            // then: APPLIED キャンセルの場合 findFirstWaitlistedForUpdate は呼ばれない
            verify(participantRepository, never()).findFirstWaitlistedForUpdate(any());
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * テスト用 OPEN 状態の listing を構築する。
     *
     * @param capacity       最大定員
     * @param confirmedCount 現在の確定参加者数
     * @param minCapacity    最小定員
     */
    private RecruitmentListingEntity buildOpenListing(int capacity, int confirmedCount, int minCapacity) throws Exception {
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
                .capacity(capacity)
                .minCapacity(minCapacity)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        setField(listing, "confirmedCount", confirmedCount);
        return listing;
    }

    /**
     * テスト用参加者エンティティを構築する。
     *
     * @param userId ユーザーID
     * @param status 参加ステータス
     */
    private RecruitmentParticipantEntity buildParticipant(Long userId, RecruitmentParticipantStatus status) throws Exception {
        RecruitmentParticipantEntity p = RecruitmentParticipantEntity.builder()
                .listingId(LISTING_ID)
                .participantType(RecruitmentParticipantType.USER)
                .userId(userId)
                .appliedBy(userId)
                .status(status)
                .build();
        setField(p, "id", userId * 100L);
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
