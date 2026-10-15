package com.mannschaft.app.promotion;

import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.mapper.PromotionMapper;
import com.mannschaft.app.promotion.repository.PromotionBillingRecordRepository;
import com.mannschaft.app.promotion.service.PromotionBillingService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PromotionBillingService 単体テスト")
class PromotionBillingServiceTest {

    @Mock private PromotionBillingRecordRepository billingRepository;
    @Mock private PromotionMapper promotionMapper;
    @InjectMocks private PromotionBillingService service;

    @Nested
    @DisplayName("listBillingRecords")
    class ListBillingRecords {

        @Test
        @DisplayName("正常系: 課金一覧が返却される")
        void 取得_正常_返却() {
            // Given
            given(billingRepository.findAllWithFilter(null, PageRequest.of(0, 10)))
                    .willReturn(new PageImpl<>(List.of()));

            // When
            Page<BillingRecordResponse> result = service.listBillingRecords(null, PageRequest.of(0, 10));

            // Then
            assertThat(result).isEmpty();
        }
    }
}
