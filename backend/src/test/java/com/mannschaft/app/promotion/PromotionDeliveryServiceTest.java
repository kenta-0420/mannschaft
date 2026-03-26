package com.mannschaft.app.promotion;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.entity.PromotionDeliveryEntity;
import com.mannschaft.app.promotion.entity.PromotionEntity;
import com.mannschaft.app.promotion.repository.PromotionDeliveryRepository;
import com.mannschaft.app.promotion.repository.PromotionRepository;
import com.mannschaft.app.promotion.service.PromotionDeliveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PromotionDeliveryService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionDeliveryService 単体テスト")
class PromotionDeliveryServiceTest {

    @Mock private PromotionDeliveryRepository deliveryRepository;
    @Mock private PromotionRepository promotionRepository;

    @InjectMocks private PromotionDeliveryService service;

    private static final Long USER_ID = 1L;

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("正常系: プロモーションが既読になる")
        void 既読_正常_保存() {
            // Given
            PromotionDeliveryEntity delivery = PromotionDeliveryEntity.builder()
                    .promotionId(10L).userId(USER_ID).channel("PUSH").build();
            given(deliveryRepository.findByIdAndUserId(1L, USER_ID))
                    .willReturn(Optional.of(delivery));
            given(deliveryRepository.save(any(PromotionDeliveryEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            service.markAsRead(USER_ID, 1L);

            // Then
            verify(deliveryRepository).save(any(PromotionDeliveryEntity.class));
        }

        @Test
        @DisplayName("異常系: 配信不在でPROMOTION_010例外")
        void 既読_不在_例外() {
            // Given
            given(deliveryRepository.findByIdAndUserId(1L, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.markAsRead(USER_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PROMOTION_010"));
        }
    }
}
