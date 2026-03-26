package com.mannschaft.app.promotion;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.dto.CreatePromotionRequest;
import com.mannschaft.app.promotion.entity.PromotionEntity;
import com.mannschaft.app.promotion.repository.PromotionDeliverySummaryRepository;
import com.mannschaft.app.promotion.repository.PromotionRepository;
import com.mannschaft.app.promotion.repository.PromotionSegmentRepository;
import com.mannschaft.app.promotion.service.PromotionService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.verify;

/**
 * {@link PromotionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionService 単体テスト")
class PromotionServiceTest {

    @Mock private PromotionRepository promotionRepository;
    @Mock private PromotionSegmentRepository segmentRepository;
    @Mock private PromotionDeliverySummaryRepository summaryRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks private PromotionService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: プロモーションが作成される")
        void 作成_正常_保存() {
            // Given
            CreatePromotionRequest req = new CreatePromotionRequest(
                    "タイトル", "本文", null, null, null, null);
            PromotionEntity saved = PromotionEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                    .title("タイトル").body("本文").build();
            given(promotionRepository.save(any(PromotionEntity.class))).willReturn(saved);
            given(segmentRepository.findByPromotionId(any())).willReturn(List.of());

            // When
            var result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(promotionRepository).save(any(PromotionEntity.class));
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("異常系: プロモーション不在でPROMOTION_001例外")
        void 取得_不在_例外() {
            // Given
            given(promotionRepository.findByIdAndScope(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.get(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PROMOTION_001"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: プロモーションが論理削除される")
        void 削除_正常_論理削除() {
            // Given
            PromotionEntity entity = PromotionEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                    .title("タイトル").build();
            given(promotionRepository.findByIdAndScope(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(promotionRepository.save(any(PromotionEntity.class))).willReturn(entity);

            // When
            service.delete(SCOPE_TYPE, SCOPE_ID, 1L);

            // Then
            verify(promotionRepository).save(any(PromotionEntity.class));
        }
    }
}
