package com.mannschaft.app.membership;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.dto.CheckinStatsResponse;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.CheckinStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

/**
 * {@link CheckinStatsService} の単体テスト。
 * チェックイン統計データの取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CheckinStatsService 単体テスト")
class CheckinStatsServiceTest {

    @Mock
    private MemberCardCheckinRepository checkinRepository;

    @Mock
    private MemberCardRepository memberCardRepository;

    @InjectMocks
    private CheckinStatsService checkinStatsService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long SCOPE_ID = 100L;

    // ========================================
    // getStats
    // ========================================

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("正常系: 統計データが返却される")
        void 取得_正常_統計返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 10);

            given(checkinRepository.countByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(50L);
            given(checkinRepository.countUniqueMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(15L);
            given(checkinRepository.countByCheckinTypeByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(
                            new Object[]{CheckinType.STAFF_SCAN, 30L},
                            new Object[]{CheckinType.SELF, 20L}));
            given(checkinRepository.countByDayOfWeek(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(
                            new Object[]{2, 10L},  // MON
                            new Object[]{4, 15L}   // WED
                    ));
            given(checkinRepository.countByHour(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(
                            new Object[]{9, 20L},
                            new Object[]{14, 30L}
                    ));

            Long topCardId = 10L;
            MemberCardEntity topCard = MemberCardEntity.builder()
                    .userId(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .cardCode("card-001")
                    .cardNumber("TEAM-0001")
                    .displayName("山田太郎")
                    .status(CardStatus.ACTIVE)
                    .checkinCount(25)
                    .totalSpend(BigDecimal.ZERO)
                    .qrSecret("secret")
                    .build();

            given(checkinRepository.findTopMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.<Object[]>of(new Object[]{topCardId, 25L}));
            given(memberCardRepository.findById(topCardId)).willReturn(Optional.of(topCard));

            given(checkinRepository.countByLocationByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.<Object[]>of(
                            new Object[]{"正面入口", CheckinType.SELF, 20L}));

            // When
            ApiResponse<CheckinStatsResponse> response =
                    checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, from, to);

            // Then
            CheckinStatsResponse stats = response.getData();
            assertThat(stats.getTotalCheckins()).isEqualTo(50L);
            assertThat(stats.getUniqueMembers()).isEqualTo(15L);
            assertThat(stats.getAveragePerDay()).isGreaterThan(0);
            assertThat(stats.getCheckinTypeBreakdown()).containsEntry("STAFF_SCAN", 30L);
            assertThat(stats.getCheckinTypeBreakdown()).containsEntry("SELF", 20L);
            assertThat(stats.getByDayOfWeek()).hasSize(2);
            assertThat(stats.getByHour()).hasSize(2);
            assertThat(stats.getTopMembers()).hasSize(1);
            assertThat(stats.getTopMembers().get(0).getCardNumber()).isEqualTo("TEAM-0001");
            assertThat(stats.getTopMembers().get(0).getCheckinCount()).isEqualTo(25L);
            assertThat(stats.getByLocation()).hasSize(1);
            assertThat(stats.getPeriod().getFrom()).isEqualTo(from);
            assertThat(stats.getPeriod().getTo()).isEqualTo(to);
        }

        @Test
        @DisplayName("正常系: チェックインなしの場合はゼロ値が返却される")
        void 取得_データなし_ゼロ値返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 7);

            given(checkinRepository.countByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(0L);
            given(checkinRepository.countUniqueMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(0L);
            given(checkinRepository.countByCheckinTypeByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByDayOfWeek(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByHour(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.findTopMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByLocationByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // When
            ApiResponse<CheckinStatsResponse> response =
                    checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, from, to);

            // Then
            CheckinStatsResponse stats = response.getData();
            assertThat(stats.getTotalCheckins()).isZero();
            assertThat(stats.getUniqueMembers()).isZero();
            assertThat(stats.getAveragePerDay()).isZero();
            assertThat(stats.getCheckinTypeBreakdown()).isEmpty();
            assertThat(stats.getByDayOfWeek()).isEmpty();
            assertThat(stats.getByHour()).isEmpty();
            assertThat(stats.getTopMembers()).isEmpty();
            assertThat(stats.getByLocation()).isEmpty();
        }

        @Test
        @DisplayName("異常系: from/toがnullでMEMBERSHIP_022例外")
        void 取得_期間null_MEMBERSHIP022例外() {
            // When / Then
            assertThatThrownBy(() -> checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_022"));
        }

        @Test
        @DisplayName("異常系: fromのみnullでMEMBERSHIP_022例外")
        void 取得_fromNull_MEMBERSHIP022例外() {
            // When / Then
            assertThatThrownBy(() -> checkinStatsService.getStats(
                    ScopeType.TEAM, SCOPE_ID, null, LocalDate.of(2026, 3, 10)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_022"));
        }

        @Test
        @DisplayName("異常系: 90日超過でMEMBERSHIP_022例外")
        void 取得_期間超過_MEMBERSHIP022例外() {
            // Given
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 6, 1); // 151日

            // When / Then
            assertThatThrownBy(() -> checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, from, to))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBERSHIP_022"));
        }

        @Test
        @DisplayName("正常系: ちょうど90日はエラーにならない")
        void 取得_90日ちょうど_正常() {
            // Given
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 4, 1); // 90日

            given(checkinRepository.countByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(0L);
            given(checkinRepository.countUniqueMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(0L);
            given(checkinRepository.countByCheckinTypeByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByDayOfWeek(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByHour(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.findTopMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByLocationByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // When
            ApiResponse<CheckinStatsResponse> response =
                    checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, from, to);

            // Then
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getPeriod().getFrom()).isEqualTo(from);
        }

        @Test
        @DisplayName("正常系: トップメンバーが10件に制限される")
        void 取得_トップメンバー_10件制限() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 10);

            given(checkinRepository.countByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(100L);
            given(checkinRepository.countUniqueMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(20L);
            given(checkinRepository.countByCheckinTypeByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByDayOfWeek(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByHour(
                    eq("TEAM"), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(checkinRepository.countByLocationByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // 15件のトップメンバーデータ
            List<Object[]> topData = new java.util.ArrayList<>();
            for (long i = 1; i <= 15; i++) {
                topData.add(new Object[]{i, 50L - i});
            }
            given(checkinRepository.findTopMembersByScopeAndPeriod(
                    eq(ScopeType.TEAM), eq(SCOPE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(topData);

            // 各カードのモック
            for (long i = 1; i <= 10; i++) {
                MemberCardEntity card = MemberCardEntity.builder()
                        .userId(i)
                        .scopeType(ScopeType.TEAM)
                        .scopeId(SCOPE_ID)
                        .cardCode("code-" + i)
                        .cardNumber("TEAM-" + String.format("%04d", i))
                        .displayName("メンバー" + i)
                        .status(CardStatus.ACTIVE)
                        .checkinCount(0)
                        .totalSpend(BigDecimal.ZERO)
                        .qrSecret("secret")
                        .build();
                given(memberCardRepository.findById(i)).willReturn(Optional.of(card));
            }

            // When
            ApiResponse<CheckinStatsResponse> response =
                    checkinStatsService.getStats(ScopeType.TEAM, SCOPE_ID, from, to);

            // Then
            assertThat(response.getData().getTopMembers()).hasSize(10);
        }
    }
}
