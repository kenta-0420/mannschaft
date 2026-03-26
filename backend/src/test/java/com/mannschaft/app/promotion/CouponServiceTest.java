package com.mannschaft.app.promotion;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.promotion.dto.CouponResponse;
import com.mannschaft.app.promotion.dto.CreateCouponRequest;
import com.mannschaft.app.promotion.dto.RedeemCouponRequest;
import com.mannschaft.app.promotion.entity.CouponDistributionEntity;
import com.mannschaft.app.promotion.entity.CouponEntity;
import com.mannschaft.app.promotion.entity.CouponRedemptionEntity;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.CouponDistributionRepository;
import com.mannschaft.app.promotion.repository.CouponRedemptionRepository;
import com.mannschaft.app.promotion.repository.CouponRepository;
import com.mannschaft.app.promotion.service.CouponService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link CouponService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponService 単体テスト")
class CouponServiceTest {

    @Mock private CouponRepository couponRepository;
    @Mock private CouponDistributionRepository distributionRepository;
    @Mock private CouponRedemptionRepository redemptionRepository;
    @Mock private PromotionMapper promotionMapper;

    @InjectMocks private CouponService service;

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    private CouponEntity createCouponEntity() {
        return CouponEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                .title("テストクーポン").couponType("PERCENTAGE")
                .discountValue(BigDecimal.TEN).maxIssues(100).maxUsesPerUser((short) 1).build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: クーポンが作成される")
        void 作成_正常_保存() {
            // Given
            CreateCouponRequest req = new CreateCouponRequest("テスト", "説明", "PERCENTAGE",
                    BigDecimal.TEN, null, 100, (short) 1, null, null);
            given(couponRepository.save(any(CouponEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(promotionMapper.toCouponResponse(any(CouponEntity.class)))
                    .willReturn(new CouponResponse(1L, "TEAM", 1L, 100L, "テスト", "説明", "PERCENTAGE",
                            BigDecimal.TEN, null, 100, 0, (short) 1, null, null, true, null, null));

            // When
            CouponResponse result = service.create(SCOPE_TYPE, SCOPE_ID, USER_ID, req);

            // Then
            assertThat(result.getTitle()).isEqualTo("テスト");
            verify(couponRepository).save(any(CouponEntity.class));
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("異常系: クーポン不在でPROMOTION_005例外")
        void 取得_不在_例外() {
            // Given
            given(couponRepository.findByIdAndScope(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.get(SCOPE_TYPE, SCOPE_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PROMOTION_005"));
        }
    }

    @Nested
    @DisplayName("redeem")
    class Redeem {

        @Test
        @DisplayName("異常系: 配布不在でPROMOTION_007例外")
        void 利用_配布不在_例外() {
            // Given
            given(distributionRepository.findByIdAndUserId(1L, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.redeem(USER_ID, 1L, new RedeemCouponRequest("detail")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PROMOTION_007"));
        }
    }

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("正常系: クーポンの有効/無効が切り替わる")
        void 切替_正常_保存() {
            // Given
            CouponEntity entity = createCouponEntity();
            given(couponRepository.findByIdAndScope(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(couponRepository.save(any(CouponEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(promotionMapper.toCouponResponse(any(CouponEntity.class)))
                    .willReturn(new CouponResponse(1L, "TEAM", 1L, 100L, "テスト", null, "PERCENTAGE",
                            BigDecimal.TEN, null, 100, 0, (short) 1, null, null, false, null, null));

            // When
            CouponResponse result = service.toggle(SCOPE_TYPE, SCOPE_ID, 1L);

            // Then
            assertThat(result).isNotNull();
            verify(couponRepository).save(any(CouponEntity.class));
        }
    }
}
