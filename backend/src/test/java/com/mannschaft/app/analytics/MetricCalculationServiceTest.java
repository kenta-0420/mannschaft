package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.service.MetricCalculationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricCalculationService 単体テスト")
class MetricCalculationServiceTest {

    @Mock private AnalyticsDailyRevenueRepository revenueRepository;
    @Mock private AnalyticsDailyUsersRepository usersRepository;
    @InjectMocks private MetricCalculationService service;

    // ========== calculateMrr ==========

    @Nested
    @DisplayName("calculateMrr")
    class CalculateMrr {

        @Test
        @DisplayName("正常系: 定期課金源のみ合算される")
        void testCalculateMrr_定期課金源のみ合算() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);

            List<AnalyticsDailyRevenueEntity> records = List.of(
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.MODULE_SUBSCRIPTION)
                            .netRevenue(new BigDecimal("10000")).build(),
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.STORAGE_ADDON)
                            .netRevenue(new BigDecimal("3000")).build(),
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.ORG_COUNT_BILLING)
                            .netRevenue(new BigDecimal("2000")).build(),
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.ADVERTISING)
                            .netRevenue(new BigDecimal("5000")).build(),
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.ONE_TIME_PAYMENT)
                            .netRevenue(new BigDecimal("8000")).build()
            );
            given(revenueRepository.findByDateBetweenOrderByDateAsc(from, to)).willReturn(records);

            // Act
            BigDecimal mrr = service.calculateMrr(from, to);

            // Assert - MODULE_SUBSCRIPTION(10000) + STORAGE_ADDON(3000) + ORG_COUNT_BILLING(2000) = 15000
            assertThat(mrr).isEqualByComparingTo(new BigDecimal("15000"));
        }

        @Test
        @DisplayName("正常系: レコードなしでゼロ")
        void testCalculateMrr_レコードなしでゼロ() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            given(revenueRepository.findByDateBetweenOrderByDateAsc(from, to)).willReturn(List.of());

            // Act
            BigDecimal mrr = service.calculateMrr(from, to);

            // Assert
            assertThat(mrr).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("正常系: 広告・アフィリエイトのみの場合ゼロ")
        void testCalculateMrr_非定期課金のみでゼロ() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            List<AnalyticsDailyRevenueEntity> records = List.of(
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.ADVERTISING)
                            .netRevenue(new BigDecimal("5000")).build(),
                    AnalyticsDailyRevenueEntity.builder()
                            .date(from).revenueSource(RevenueSource.AFFILIATE)
                            .netRevenue(new BigDecimal("3000")).build()
            );
            given(revenueRepository.findByDateBetweenOrderByDateAsc(from, to)).willReturn(records);

            // Act
            BigDecimal mrr = service.calculateMrr(from, to);

            // Assert
            assertThat(mrr).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ========== calculateArr ==========

    @Nested
    @DisplayName("calculateArr")
    class CalculateArr {

        @Test
        @DisplayName("正常系: MRR x 12")
        void testCalculateArr_正常系() {
            // Arrange
            BigDecimal mrr = new BigDecimal("100000");

            // Act
            BigDecimal arr = service.calculateArr(mrr);

            // Assert
            assertThat(arr).isEqualByComparingTo(new BigDecimal("1200000"));
        }
    }

    // ========== calculateArpu ==========

    @Nested
    @DisplayName("calculateArpu")
    class CalculateArpu {

        @Test
        @DisplayName("正常系: 純収益 / アクティブユーザー数")
        void testCalculateArpu_正常系() {
            // Arrange
            BigDecimal netRevenue = new BigDecimal("50000");
            int activeUsers = 100;

            // Act
            BigDecimal arpu = service.calculateArpu(netRevenue, activeUsers);

            // Assert
            assertThat(arpu).isEqualByComparingTo(new BigDecimal("500.00"));
        }

        @Test
        @DisplayName("異常系: activeUsers=0 で null")
        void testCalculateArpu_アクティブユーザーゼロでnull() {
            // Act
            BigDecimal arpu = service.calculateArpu(new BigDecimal("50000"), 0);

            // Assert
            assertThat(arpu).isNull();
        }
    }

    // ========== calculateLtv ==========

    @Nested
    @DisplayName("calculateLtv")
    class CalculateLtv {

        @Test
        @DisplayName("正常系: ARPU / チャーンレート")
        void testCalculateLtv_正常系() {
            // Arrange
            BigDecimal arpu = new BigDecimal("500");
            BigDecimal churnRate = new BigDecimal("5"); // 5%

            // Act
            BigDecimal ltv = service.calculateLtv(arpu, churnRate);

            // Assert - 500 / 0.05 = 10000
            assertThat(ltv).isEqualByComparingTo(new BigDecimal("10000.00"));
        }

        @Test
        @DisplayName("異常系: churnRate=0 で null")
        void testCalculateLtv_チャーンレートゼロでnull() {
            // Act
            BigDecimal ltv = service.calculateLtv(new BigDecimal("500"), BigDecimal.ZERO);

            // Assert
            assertThat(ltv).isNull();
        }

        @Test
        @DisplayName("異常系: arpu=null で null")
        void testCalculateLtv_ARPUがnullでnull() {
            // Act
            BigDecimal ltv = service.calculateLtv(null, new BigDecimal("5"));

            // Assert
            assertThat(ltv).isNull();
        }
    }

    // ========== calculateNrr ==========

    @Nested
    @DisplayName("calculateNrr")
    class CalculateNrr {

        @Test
        @DisplayName("正常系: (月初MRR + Expansion - Churned) / 月初MRR x 100")
        void testCalculateNrr_正常系() {
            // Arrange
            BigDecimal beginningMrr = new BigDecimal("100000");
            BigDecimal expansionMrr = new BigDecimal("10000");
            BigDecimal churnedMrr = new BigDecimal("5000");

            // Act
            BigDecimal nrr = service.calculateNrr(beginningMrr, expansionMrr, churnedMrr);

            // Assert - (100000 + 10000 - 5000) / 100000 * 100 = 105.0000
            assertThat(nrr).isEqualByComparingTo(new BigDecimal("105.0000"));
        }

        @Test
        @DisplayName("異常系: beginningMrr=0 で null")
        void testCalculateNrr_月初MRRゼロでnull() {
            // Act
            BigDecimal nrr = service.calculateNrr(BigDecimal.ZERO, new BigDecimal("10000"), new BigDecimal("5000"));

            // Assert
            assertThat(nrr).isNull();
        }
    }

    // ========== calculateQuickRatio ==========

    @Nested
    @DisplayName("calculateQuickRatio")
    class CalculateQuickRatio {

        @Test
        @DisplayName("正常系: (新規 + 復活 + Expansion) / Churned")
        void testCalculateQuickRatio_正常系() {
            // Arrange
            BigDecimal newMrr = new BigDecimal("20000");
            BigDecimal reactivationMrr = new BigDecimal("5000");
            BigDecimal expansionMrr = new BigDecimal("10000");
            BigDecimal churnedMrr = new BigDecimal("7000");

            // Act
            BigDecimal qr = service.calculateQuickRatio(newMrr, reactivationMrr, expansionMrr, churnedMrr);

            // Assert - (20000 + 5000 + 10000) / 7000 = 5.0000
            assertThat(qr).isEqualByComparingTo(new BigDecimal("5.0000"));
        }

        @Test
        @DisplayName("異常系: churnedMrr=0 で null")
        void testCalculateQuickRatio_ChurnedゼロでNull() {
            // Act
            BigDecimal qr = service.calculateQuickRatio(
                    new BigDecimal("20000"), new BigDecimal("5000"),
                    new BigDecimal("10000"), BigDecimal.ZERO);

            // Assert
            assertThat(qr).isNull();
        }
    }

    // ========== calculateUserChurnRate ==========

    @Nested
    @DisplayName("calculateUserChurnRate")
    class CalculateUserChurnRate {

        @Test
        @DisplayName("正常系: 解約数 / 月初課金ユーザー数 x 100")
        void testCalculateUserChurnRate_正常系() {
            // Act
            BigDecimal rate = service.calculateUserChurnRate(5, 100);

            // Assert - 5 / 100 * 100 = 5.00
            assertThat(rate).isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("異常系: beginningPayingUsers=0 で ZERO")
        void testCalculateUserChurnRate_月初ゼロでZERO() {
            // Act
            BigDecimal rate = service.calculateUserChurnRate(5, 0);

            // Assert
            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ========== calculateRevenueChurnRate ==========

    @Nested
    @DisplayName("calculateRevenueChurnRate")
    class CalculateRevenueChurnRate {

        @Test
        @DisplayName("正常系: 解約MRR / 月初MRR x 100")
        void testCalculateRevenueChurnRate_正常系() {
            // Arrange
            BigDecimal churnedMrr = new BigDecimal("5000");
            BigDecimal beginningMrr = new BigDecimal("100000");

            // Act
            BigDecimal rate = service.calculateRevenueChurnRate(churnedMrr, beginningMrr);

            // Assert - 5000 / 100000 * 100 = 5.00
            assertThat(rate).isEqualByComparingTo(new BigDecimal("5.00"));
        }

        @Test
        @DisplayName("異常系: beginningMrr=0 で ZERO")
        void testCalculateRevenueChurnRate_月初MRRゼロでZERO() {
            // Act
            BigDecimal rate = service.calculateRevenueChurnRate(new BigDecimal("5000"), BigDecimal.ZERO);

            // Assert
            assertThat(rate).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
