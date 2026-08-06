package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.residencestatus.ResidenceStatusErrorCode;
import com.mannschaft.app.residencestatus.dto.AnnualReviewResponseDto;
import com.mannschaft.app.residencestatus.dto.SubmitAnnualResponseRequest;
import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.entity.AnnualReviewResponse;
import com.mannschaft.app.residencestatus.repository.AnnualReviewRepository;
import com.mannschaft.app.residencestatus.repository.AnnualReviewResponseRepository;
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

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AnnualReviewResponseService} のユニットテスト（F09.16 S3-A）。
 *
 * <p>外部依存（Repository / AccessControlService）はすべて Mockito スタブ化する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnualReviewResponseService")
class AnnualReviewResponseServiceTest {

    @Mock
    private AnnualReviewRepository annualReviewRepo;
    @Mock
    private AnnualReviewResponseRepository responseRepo;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ResidentRegistryRepository residentRegistryRepository;

    @InjectMocks
    private AnnualReviewResponseService service;

    static final Long ORG_ID = 100L;
    static final Long ADMIN_USER = 1001L;
    static final Long RESIDENT_USER = 2001L;
    static final Long RESIDENT_REGISTRY_ID = 3001L;
    static final Long DWELLING_UNIT_ID = 4001L;

    // ─────────────────────────────────────────────
    // submitResponse
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("submitResponse")
    class SubmitResponse {

        private UUID reviewId;
        private AnnualReview openReview;
        private SubmitAnnualResponseRequest validRequest;

        @BeforeEach
        void setUp() {
            reviewId = UUID.randomUUID();
            openReview = buildOpenReview(reviewId, ORG_ID, 0);
            validRequest = SubmitAnnualResponseRequest.builder()
                    .dwellingUnitId(DWELLING_UNIT_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .residenceState("OWNER_RESIDING")
                    .contactPhoneVerified(true)
                    .contactEmailVerified(true)
                    .emergencyContactVerified(false)
                    .note("確認しました")
                    .build();
        }

        @Test
        @DisplayName("正常系（新規）: 回答が作成され responseCount がインクリメントされる")
        void submitResponse_new_increments_count() {
            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(openReview));
            when(residentRegistryRepository.findById(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(registryOwnedBy(RESIDENT_USER)));
            when(responseRepo.findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(reviewId, RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());
            when(responseRepo.save(any(AnnualReviewResponse.class)))
                    .thenAnswer(inv -> {
                        AnnualReviewResponse e = inv.getArgument(0);
                        setField(e, "id", UUID.randomUUID());
                        return e;
                    });
            when(annualReviewRepo.save(any(AnnualReview.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AnnualReviewResponseDto dto = service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, validRequest);

            assertThat(dto.getResidenceState()).isEqualTo("OWNER_RESIDING");
            assertThat(dto.getRespondentUserId()).isEqualTo(RESIDENT_USER);

            // responseCount がインクリメントされていること
            ArgumentCaptor<AnnualReview> reviewCaptor = ArgumentCaptor.forClass(AnnualReview.class);
            verify(annualReviewRepo).save(reviewCaptor.capture());
            assertThat(reviewCaptor.getValue().getResponseCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常系（更新・UPSERT）: 既存回答が更新され responseCount は変化しない")
        void submitResponse_update_does_not_increment_count() {
            AnnualReviewResponse existing = buildResponse(UUID.randomUUID(), reviewId, RESIDENT_REGISTRY_ID, "RENTED_OUT");
            openReview.setResponseCount(1);

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(openReview));
            when(residentRegistryRepository.findById(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(registryOwnedBy(RESIDENT_USER)));
            when(responseRepo.findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(reviewId, RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(existing));
            when(responseRepo.save(any(AnnualReviewResponse.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            AnnualReviewResponseDto dto = service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, validRequest);

            assertThat(dto.getResidenceState()).isEqualTo("OWNER_RESIDING");
            // annualReviewRepo.save は呼ばれない（responseCount 変化なし）
            verify(annualReviewRepo, never()).save(any());
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_NOT_FOUND: 存在しないキャンペーン ID の場合")
        void submitResponse_review_not_found() {
            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, validRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND);

            verify(responseRepo, never()).save(any());
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_ALREADY_CLOSED: クローズ済みキャンペーンへの回答は拒否される")
        void submitResponse_review_already_closed() {
            AnnualReview closedReview = buildOpenReview(reviewId, ORG_ID, 0);
            closedReview.close();

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(closedReview));

            assertThatThrownBy(() -> service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, validRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_ALREADY_CLOSED);

            verify(responseRepo, never()).save(any());
        }

        @Test
        @DisplayName("RESIDENCE_STATE_INVALID: 無効な residenceState の場合")
        void submitResponse_invalid_residence_state() {
            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(openReview));
            when(residentRegistryRepository.findById(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.of(registryOwnedBy(RESIDENT_USER)));

            SubmitAnnualResponseRequest invalidReq = SubmitAnnualResponseRequest.builder()
                    .dwellingUnitId(DWELLING_UNIT_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .residenceState("INVALID_STATE")
                    .build();

            assertThatThrownBy(() -> service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, invalidReq))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.RESIDENCE_STATE_INVALID);

            verify(responseRepo, never()).save(any());
        }

        @Test
        @DisplayName("正常系: すべての有効な residenceState 値が受け付けられる")
        void submitResponse_all_valid_states_accepted() {
            String[] validStates = {"OWNER_RESIDING", "RENTED_OUT", "LONG_ABSENCE", "VACANT", "OTHER"};

            for (String state : validStates) {
                UUID rid = UUID.randomUUID();
                AnnualReview review = buildOpenReview(rid, ORG_ID, 0);

                when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(rid, ORG_ID))
                        .thenReturn(Optional.of(review));
                when(residentRegistryRepository.findById(RESIDENT_REGISTRY_ID))
                        .thenReturn(Optional.of(registryOwnedBy(RESIDENT_USER)));
                when(responseRepo.findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(eq(rid), anyLong()))
                        .thenReturn(Optional.empty());
                when(responseRepo.save(any(AnnualReviewResponse.class)))
                        .thenAnswer(inv -> {
                            AnnualReviewResponse e = inv.getArgument(0);
                            setField(e, "id", UUID.randomUUID());
                            return e;
                        });
                when(annualReviewRepo.save(any(AnnualReview.class)))
                        .thenAnswer(inv -> inv.getArgument(0));

                SubmitAnnualResponseRequest req = SubmitAnnualResponseRequest.builder()
                        .dwellingUnitId(DWELLING_UNIT_ID)
                        .residentRegistryId(RESIDENT_REGISTRY_ID)
                        .residenceState(state)
                        .build();

                AnnualReviewResponseDto dto = service.submitResponse(ORG_ID, rid, RESIDENT_USER, req);
                assertThat(dto.getResidenceState()).isEqualTo(state);
            }
        }

        /**
         * 認可根治戦役 Wave6 ロットC の根治対象: 旧実装は {@code req.getResidentRegistryId()} の
         * 所有権を一切検証せず、他居住者の {@code residentRegistryId} を指定して居住状態・連絡先確認
         * フラグを書き換えられた（未検証の引数が永続記録へ到達する BOLA）。
         * 他ユーザーが所有する residentRegistryId を指定した場合に拒否されることを固定する。
         */
        @Test
        @DisplayName("ANNUAL_REVIEW_RESPONSE_NOT_FOUND: 他ユーザーが所有する residentRegistryId を"
                + "指定した回答は拒否される（他居住者の回答書き換えを根治）")
        void submitResponse_residentRegistry_owned_by_other_user_denied() {
            Long otherUsersRegistryId = 9999L;
            SubmitAnnualResponseRequest req = SubmitAnnualResponseRequest.builder()
                    .dwellingUnitId(DWELLING_UNIT_ID)
                    .residentRegistryId(otherUsersRegistryId)
                    .residenceState("OWNER_RESIDING")
                    .build();

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(openReview));
            when(residentRegistryRepository.findById(otherUsersRegistryId))
                    .thenReturn(Optional.of(registryOwnedBy(8888L)));

            assertThatThrownBy(() -> service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_RESPONSE_NOT_FOUND);

            verify(responseRepo, never()).save(any());
            verify(annualReviewRepo, never()).save(any());
        }

        @Test
        @DisplayName("ANNUAL_REVIEW_RESPONSE_NOT_FOUND: 存在しない residentRegistryId を指定した場合")
        void submitResponse_residentRegistry_not_found() {
            SubmitAnnualResponseRequest req = SubmitAnnualResponseRequest.builder()
                    .dwellingUnitId(DWELLING_UNIT_ID)
                    .residentRegistryId(RESIDENT_REGISTRY_ID)
                    .residenceState("OWNER_RESIDING")
                    .build();

            when(annualReviewRepo.findByIdAndOrganizationIdAndDeletedAtIsNull(reviewId, ORG_ID))
                    .thenReturn(Optional.of(openReview));
            when(residentRegistryRepository.findById(RESIDENT_REGISTRY_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.submitResponse(ORG_ID, reviewId, RESIDENT_USER, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ResidenceStatusErrorCode.ANNUAL_REVIEW_RESPONSE_NOT_FOUND);

            verify(responseRepo, never()).save(any());
        }
    }

    // ─────────────────────────────────────────────
    // listResponses
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("listResponses")
    class ListResponses {

        @Test
        @DisplayName("正常系: 回答一覧が返される")
        void listResponses_success() {
            UUID reviewId = UUID.randomUUID();
            UUID r1 = UUID.randomUUID();
            UUID r2 = UUID.randomUUID();
            when(responseRepo.findByAnnualReviewIdAndDeletedAtIsNull(reviewId))
                    .thenReturn(List.of(
                            buildResponse(r1, reviewId, 3001L, "OWNER_RESIDING"),
                            buildResponse(r2, reviewId, 3002L, "RENTED_OUT")));

            List<AnnualReviewResponseDto> list = service.listResponses(ORG_ID, reviewId, ADMIN_USER);

            assertThat(list).hasSize(2);
            assertThat(list.get(0).getId()).isEqualTo(r1);
            assertThat(list.get(1).getId()).isEqualTo(r2);
        }

        @Test
        @DisplayName("権限なし: checkAdminOrAbove が例外をスローする場合")
        void listResponses_access_denied() {
            UUID reviewId = UUID.randomUUID();
            doThrow(new BusinessException(ResidenceStatusErrorCode.ANNUAL_REVIEW_NOT_FOUND))
                    .when(accessControlService).checkAdminOrAbove(RESIDENT_USER, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.listResponses(ORG_ID, reviewId, RESIDENT_USER))
                    .isInstanceOf(BusinessException.class);

            verify(responseRepo, never()).findByAnnualReviewIdAndDeletedAtIsNull(any());
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private AnnualReview buildOpenReview(UUID id, Long orgId, int responseCount) {
        AnnualReview review = AnnualReview.builder()
                .organizationId(orgId)
                .reviewYear(2026)
                .startedAt(LocalDateTime.now().minusDays(5))
                .deadlineAt(LocalDateTime.now().plusDays(25))
                .targetCount(10)
                .responseCount(responseCount)
                .createdBy(ADMIN_USER)
                .build();
        setField(review, "id", id);
        return review;
    }

    private AnnualReviewResponse buildResponse(UUID id, UUID reviewId, Long residentRegistryId, String state) {
        AnnualReviewResponse response = AnnualReviewResponse.builder()
                .organizationId(ORG_ID)
                .annualReviewId(reviewId)
                .dwellingUnitId(DWELLING_UNIT_ID)
                .residentRegistryId(residentRegistryId)
                .respondentUserId(RESIDENT_USER)
                .residenceState(state)
                .contactPhoneVerified(true)
                .contactEmailVerified(false)
                .emergencyContactVerified(false)
                .respondedAt(LocalDateTime.now())
                .build();
        setField(response, "id", id);
        return response;
    }

    private ResidentRegistryEntity registryOwnedBy(Long userId) {
        ResidentRegistryEntity registry = mock(ResidentRegistryEntity.class);
        when(registry.getUserId()).thenReturn(userId);
        return registry;
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
