package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.AnnualReviewDto;
import com.mannschaft.app.residencestatus.dto.CreateAnnualReviewRequest;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.event.AnnualReviewClosedEvent;
import com.mannschaft.app.residencestatus.event.AnnualReviewStartedEvent;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnnualReviewService} のユニットテスト（F09.16 S3-A）。
 *
 * <p>外部依存（Repository / AccessControlService / EventPublisher）はすべて Mockito スタブ化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnualReviewService")
class AnnualReviewServiceTest {

    @Mock
    private AnnualReviewRepository annualReviewRepo;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private ResidentRegistryRepository residentRegistryRepository;

    @InjectMocks
    private AnnualReviewService service;

    static final Long ORG_ID = 100L;
    static final Long ADMIN_USER = 1001L;
    static final Long MEMBER_USER = 1002L;

    // ─────────────────────────────────────────────
    // createReview
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("createReview")
    class CreateReview {

        private CreateAnnualReviewRequest validRequest;

        @BeforeEach
        void setUp() {
            validRequest = CreateAnnualReviewRequest.builder()
                    .reviewYear(2026)
                    .deadlineAt(LocalDateTime.now().plusDays(30))
                    .message("居住実態確認のご協力をお願いします")
                    .build();
        }

        @Test
        @DisplayName("正常系: キャンペーンが作成され AnnualReviewStartedEvent が発火する")
        void createReview_success() {
            when(annualReviewRepo.findByOrganizationIdAndReviewYearAndDeletedAtIsNull(ORG_ID, 2026))
                    .thenReturn(Optional.empty());

            UUID savedId = UUID.randomUUID();
            when(annualReviewRepo.save(any(AnnualReview.class)))
                    .thenAnswer(inv -> {
                        AnnualReview e = inv.getArgument(0);
                        setField(e, "id", savedId);
                        return e;
                    });

            AnnualReviewDto dto = service.createReview(ORG_ID, ADMIN_USER, validRequest);

            assertThat(dto.getId()).isEqualTo(savedId);
            assertThat(dto.getReviewYear()).isEqualTo(2026);
            assertThat(dto.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(dto.getClosedAt()).isNull();

            ArgumentCaptor<AnnualReviewStartedEvent> eventCaptor =
                    ArgumentCaptor.forClass(AnnualReviewStartedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue().getAnnualReviewId()).isEqualTo(savedId);
            assertThat(eventCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_YEAR_CONFLICT: 同年度のキャンペーンが既に存在する場合")
        void createReview_year_conflict() {
            AnnualReview existing = buildReview(UUID.randomUUID(), ORG_ID, 2026, null);
            when(annualReviewRepo.findByOrganizationIdAndReviewYearAndDeletedAtIsNull(ORG_ID, 2026))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.createReview(ORG_ID, ADMIN_USER, validRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_YEAR_CONFLICT);

            verify(annualReviewRepo, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("権限なし: checkAdminOrAbove が例外をスローする場合")
        void createReview_access_denied() {
            doThrow(new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND))
                    .when(accessControlService).checkAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.createReview(ORG_ID, MEMBER_USER, validRequest))
                    .isInstanceOf(BusinessException.class);

            verify(annualReviewRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // closeReview
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("closeReview")
    class CloseReview {

        @Test
        @DisplayName("正常系: クローズすると closedAt がセットされ AnnualReviewClosedEvent が発火する")
        void closeReview_success() {
            UUID reviewId = UUID.randomUUID();
            AnnualReview review = buildReview(reviewId, ORG_ID, 2026, null);
            review.setResponseCount(5);
            review.setTargetCount(10);

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(review));
            when(annualReviewRepo.save(any(AnnualReview.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AnnualReviewDto dto = service.closeReview(ORG_ID, reviewId, ADMIN_USER);

            assertThat(dto.getClosedAt()).isNotNull();

            ArgumentCaptor<AnnualReviewClosedEvent> eventCaptor =
                    ArgumentCaptor.forClass(AnnualReviewClosedEvent.class);
            verify(eventPublisher).publishEvent(eventCaptor.capture());
            AnnualReviewClosedEvent event = eventCaptor.getValue();
            assertThat(event.getAnnualReviewId()).isEqualTo(reviewId);
            assertThat(event.getResponseCount()).isEqualTo(5);
            assertThat(event.getTotalCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_ALREADY_CLOSED: 既にクローズ済みのキャンペーンをクローズしようとする場合")
        void closeReview_already_closed() {
            UUID reviewId = UUID.randomUUID();
            AnnualReview review = buildReview(reviewId, ORG_ID, 2026, LocalDateTime.now().minusDays(1));

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(review));

            assertThatThrownBy(() -> service.closeReview(ORG_ID, reviewId, ADMIN_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_ALREADY_CLOSED);

            verify(annualReviewRepo, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_NOT_FOUND: 存在しないキャンペーン ID を指定した場合")
        void closeReview_not_found() {
            UUID reviewId = UUID.randomUUID();
            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.closeReview(ORG_ID, reviewId, ADMIN_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────
    // autoCloseExpiredReviews
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("autoCloseExpiredReviews")
    class AutoCloseExpiredReviews {

        @Test
        @DisplayName("締切超過0件の場合: save・publishEvent が呼ばれない")
        void autoClose_zero_expired() {
            when(annualReviewRepo.findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(any()))
                    .thenReturn(List.of());

            service.autoCloseExpiredReviews();

            verify(annualReviewRepo, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }

        @Test
        @DisplayName("締切超過1件の場合: 1件クローズされ AnnualReviewClosedEvent が1回発火する")
        void autoClose_one_expired() {
            UUID reviewId = UUID.randomUUID();
            AnnualReview review = buildReview(reviewId, ORG_ID, 2025, null);
            review.setResponseCount(3);
            review.setTargetCount(8);

            when(annualReviewRepo.findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(any()))
                    .thenReturn(List.of(review));
            when(annualReviewRepo.save(any(AnnualReview.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.autoCloseExpiredReviews();

            verify(annualReviewRepo, times(1)).save(any());
            verify(eventPublisher, times(1)).publishEvent(any(AnnualReviewClosedEvent.class));
        }

        @Test
        @DisplayName("締切超過2件の場合: 2件全てクローズされ AnnualReviewClosedEvent が2回発火する")
        void autoClose_two_expired() {
            UUID reviewId1 = UUID.randomUUID();
            UUID reviewId2 = UUID.randomUUID();
            AnnualReview review1 = buildReview(reviewId1, ORG_ID, 2024, null);
            AnnualReview review2 = buildReview(reviewId2, 200L, 2025, null);

            when(annualReviewRepo.findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(any()))
                    .thenReturn(List.of(review1, review2));
            when(annualReviewRepo.save(any(AnnualReview.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.autoCloseExpiredReviews();

            verify(annualReviewRepo, times(2)).save(any());
            verify(eventPublisher, times(2)).publishEvent(any(AnnualReviewClosedEvent.class));
        }
    }

    // ─────────────────────────────────────────────
    // listReviews
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("listReviews")
    class ListReviews {

        @Test
        @DisplayName("正常系: キャンペーン一覧が返される")
        void listReviews_success() {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of(
                            buildReview(id1, ORG_ID, 2025, LocalDateTime.now().minusDays(1)),
                            buildReview(id2, ORG_ID, 2026, null)));

            List<AnnualReviewDto> list = service.listReviews(ORG_ID, ADMIN_USER);

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getId()).isEqualTo(id1);
            assertThat(list.get(1).getId()).isEqualTo(id2);
        }

        @Test
        @DisplayName("権限なし: checkAdminOrAbove が例外をスローする場合")
        void listReviews_access_denied() {
            doThrow(new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND))
                    .when(accessControlService).checkAdminOrAbove(MEMBER_USER, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.listReviews(ORG_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class);

            verify(annualReviewRepo, never()).findByOrganizationIdAndDeletedAtIsNull(anyLong());
        }
    }

    // ─────────────────────────────────────────────
    // listMyReviews
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("listMyReviews")
    class ListMyReviews {

        @Test
        @DisplayName("正常系: 当該組織の現居住者には未クローズのキャンペーンのみ返される")
        void listMyReviews_only_open() {
            UUID openId = UUID.randomUUID();
            UUID closedId = UUID.randomUUID();
            when(residentRegistryRepository.findActiveByUserIdAndOrganizationId(MEMBER_USER, ORG_ID))
                    .thenReturn(Optional.of(mock(ResidentRegistryEntity.class)));
            when(annualReviewRepo.findByOrganizationIdAndDeletedAtIsNull(ORG_ID))
                    .thenReturn(List.of(
                            buildReview(openId, ORG_ID, 2026, null),
                            buildReview(closedId, ORG_ID, 2025, LocalDateTime.now().minusDays(1))));

            List<AnnualReviewDto> list = service.listMyReviews(ORG_ID, MEMBER_USER);

            assertThat(list).hasSize(1);
            assertThat(list.get(0).getId()).isEqualTo(openId);
        }

        /**
         * 認可根治戦役 Wave6 ロットC の根治対象: 旧実装は {@code requestUserId} を検索条件に
         * 一切使わず、非居住者でも任意の {@code organizationId} を指定してキャンペーン一覧を
         * 取得できた（死んだ引数・BOLA）。当該組織の居住者台帳が無いユーザーは拒否されることを固定する。
         */
        @Test
        @DisplayName("ANNUAL_REVIEW_NOT_FOUND: 当該組織の居住者台帳を持たないユーザーは拒否される"
                + "（非居住者による他組織キャンペーン一覧の閲覧を根治）")
        void listMyReviews_non_resident_denied() {
            when(residentRegistryRepository.findActiveByUserIdAndOrganizationId(MEMBER_USER, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listMyReviews(ORG_ID, MEMBER_USER))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND);

            verify(annualReviewRepo, never()).findByOrganizationIdAndDeletedAtIsNull(anyLong());
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private AnnualReview buildReview(UUID id, Long orgId, int year, LocalDateTime closedAt) {
        AnnualReview review = AnnualReview.builder()
                .organizationId(orgId)
                .reviewYear(year)
                .startedAt(LocalDateTime.now().minusDays(5))
                .deadlineAt(LocalDateTime.now().plusDays(25))
                .targetCount(10)
                .responseCount(0)
                .createdBy(ADMIN_USER)
                .build();
        setField(review, "id", id);
        if (closedAt != null) {
            review.setClosedAt(closedAt);
        }
        return review;
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
