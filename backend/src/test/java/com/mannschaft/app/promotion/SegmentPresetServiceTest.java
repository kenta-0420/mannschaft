package com.mannschaft.app.promotion;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.dto.CreateSegmentPresetRequest;
import com.mannschaft.app.promotion.dto.SegmentPresetResponse;
import com.mannschaft.app.promotion.entity.SavedSegmentPresetEntity;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.SavedSegmentPresetRepository;
import com.mannschaft.app.promotion.service.SegmentPresetService;
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
 * {@link SegmentPresetService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SegmentPresetService 単体テスト")
class SegmentPresetServiceTest {

    @Mock private SavedSegmentPresetRepository presetRepository;
    @Mock private PromotionMapper promotionMapper;

    @InjectMocks private SegmentPresetService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: プリセットが作成される")
        void 作成_正常_保存() {
            // Given
            CreateSegmentPresetRequest req = new CreateSegmentPresetRequest("テスト", "{}");
            given(presetRepository.save(any(SavedSegmentPresetEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(promotionMapper.toSegmentPresetResponse(any(SavedSegmentPresetEntity.class)))
                    .willReturn(new SegmentPresetResponse(1L, "TEAM", 1L, "テスト", "{}", 100L, null, null));

            // When
            SegmentPresetResponse result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // Then
            assertThat(result.getName()).isEqualTo("テスト");
            verify(presetRepository).save(any(SavedSegmentPresetEntity.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: プリセット不在でPROMOTION_011例外")
        void 削除_不在_例外() {
            // Given
            given(presetRepository.findByIdAndScope(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.delete(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PROMOTION_011"));
        }
    }
}
