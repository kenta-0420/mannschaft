package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link RecruitmentListingService} の単体テスト。
 * §5.1 募集作成のバリデーションと §5.7 編集制約を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingService 単体テスト")
class RecruitmentListingServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentCategoryRepository categoryRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private RecruitmentMapper mapper;

    @InjectMocks
    private RecruitmentListingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 100L;
    private static final Long LISTING_ID = 200L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.now();

    // ========================================
    // create - §5.1 バリデーション
    // ========================================

    @Nested
    @DisplayName("create - §5.1 バリデーション")
    class CreateValidation {

        @Test
        @DisplayName("category_id が存在しない → CATEGORY_NOT_SPECIFIED")
        void create_categoryNotFound_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(false);

            CreateRecruitmentListingRequest request = validRequest();
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED);
        }

        @Test
        @DisplayName("min_capacity > capacity → INVALID_CAPACITY")
        void create_minCapacityExceedsCapacity_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = new CreateRecruitmentListingRequest(
                    CATEGORY_ID, null, "test", null,
                    RecruitmentParticipationType.INDIVIDUAL,
                    BASE_TIME.plusDays(2),
                    BASE_TIME.plusDays(2).plusHours(2),
                    BASE_TIME.plusDays(1),
                    BASE_TIME.plusDays(1),
                    5, 10, // capacity=5, minCapacity=10 → 不正
                    false, null,
                    RecruitmentVisibility.SCOPE_ONLY,
                    null, null, null, null);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_CAPACITY);
        }

        @Test
        @DisplayName("payment_enabled=true で price=null → PRICE_REQUIRED")
        void create_paymentEnabledWithoutPrice_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = new CreateRecruitmentListingRequest(
                    CATEGORY_ID, null, "test", null,
                    RecruitmentParticipationType.INDIVIDUAL,
                    BASE_TIME.plusDays(2),
                    BASE_TIME.plusDays(2).plusHours(2),
                    BASE_TIME.plusDays(1),
                    BASE_TIME.plusDays(1),
                    10, 1,
                    true, null, // paymentEnabled=true, price=null
                    RecruitmentVisibility.SCOPE_ONLY,
                    null, null, null, null);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.PRICE_REQUIRED);
        }
    }

    // ========================================
    // update - §5.7 編集制約
    // ========================================

    @Nested
    @DisplayName("update - §5.7 編集制約")
    class UpdateConstraints {

        @Test
        @DisplayName("capacity を confirmed_count 未満に変更 → CAPACITY_BELOW_CONFIRMED")
        void update_capacityBelowConfirmed_throws() throws Exception {
            RecruitmentListingEntity listing = buildListingWithConfirmed(5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            UpdateRecruitmentListingRequest request = new UpdateRecruitmentListingRequest(
                    null, null, null, null, null, null, null,
                    3, // capacity=3 < confirmed_count=5
                    null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.update(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CAPACITY_BELOW_CONFIRMED);
        }

        @Test
        @DisplayName("listing not found → LISTING_NOT_FOUND")
        void update_notFound_throws() {
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(LISTING_ID, USER_ID,
                    new UpdateRecruitmentListingRequest(null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private CreateRecruitmentListingRequest validRequest() {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "test title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                BASE_TIME.plusDays(2),
                BASE_TIME.plusDays(2).plusHours(2),
                BASE_TIME.plusDays(1),
                BASE_TIME.plusDays(1),
                10, 1,
                false, null,
                RecruitmentVisibility.SCOPE_ONLY,
                "東京", null, null, null);
    }

    private RecruitmentListingEntity buildListingWithConfirmed(int confirmedCount) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(CATEGORY_ID)
                .title("test")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(BASE_TIME.plusDays(2))
                .endAt(BASE_TIME.plusDays(2).plusHours(2))
                .applicationDeadline(BASE_TIME.plusDays(1))
                .autoCancelAt(BASE_TIME.plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "confirmedCount", confirmedCount);
        return listing;
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
