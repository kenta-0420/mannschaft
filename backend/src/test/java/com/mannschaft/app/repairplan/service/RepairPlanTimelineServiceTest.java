package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.repairplan.dto.RepairPlanTimelineResponse;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

/**
 * F08.8 Phase 3: {@link RepairPlanTimelineService} の単体テスト。
 *
 * <p>検証範囲:</p>
 * <ul>
 *   <li>yearFrom/yearTo null の場合デフォルト値（現在年-20、+10）が適用される</li>
 *   <li>複数カテゴリの金額が正しく集計される</li>
 *   <li>CPI が 2024 年で 100.0 を返す</li>
 *   <li>CPI が 2025 年で 101.5 を返す</li>
 *   <li>理事長が在任期間内で正しく解決される</li>
 *   <li>該当年度に理事長がいない場合は chairpersonByYear に含まれない</li>
 *   <li>yearFrom &gt; yearTo の場合は from = to - 1 に補正される</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepairPlanTimelineService 単体テスト")
class RepairPlanTimelineServiceTest {

    @Mock
    private RepairPlanItemRepository repairPlanItemRepository;

    @Mock
    private TeamMemberTermRepository teamMemberTermRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RepairPlanTimelineService timelineService;

    private static final Long SCOPE_ID = 100L;
    private static final String SCOPE_TYPE = "TEAM";

    // ========================================
    // デフォルト年度範囲
    // ========================================

    @Nested
    @DisplayName("デフォルト年度範囲の適用")
    class DefaultYearRange {

        @Test
        @DisplayName("yearFrom/yearTo が null の場合デフォルト値（現在年-20、現在年+10）が適用される")
        void デフォルト年度範囲が適用される() {
            int currentYear = LocalDate.now().getYear();
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    anyString(), anyLong()))
                    .willReturn(List.of());

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, null, null);

            assertThat(response.yearFrom()).isEqualTo(currentYear - 20);
            assertThat(response.yearTo()).isEqualTo(currentYear + 10);
            assertThat(response.labels()).hasSize(31); // 20 + 1(基準年) + 10
        }
    }

    // ========================================
    // 金額集計
    // ========================================

    @Nested
    @DisplayName("複数カテゴリの金額集計")
    class AmountAggregation {

        @Test
        @DisplayName("複数カテゴリの金額が正しく集計される")
        void 複数カテゴリの金額が集計される() {
            // Object[]{plannedYear, category, sumAmount, minutesNotes}
            List<Object[]> rows = List.of(
                    new Object[]{2030, "外壁", 10_000_000L, null},
                    new Object[]{2030, "屋根", 5_000_000L, null},
                    new Object[]{2031, "外壁", 8_000_000L, null}
            );
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), anyInt(), anyInt()))
                    .willReturn(rows);
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    anyString(), anyLong()))
                    .willReturn(List.of());

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2029, 2032);

            // amountByYearAndCategory の確認
            assertThat(response.amountByYearAndCategory()).containsKey("2030");
            assertThat(response.amountByYearAndCategory().get("2030")).containsEntry("外壁", 10_000_000L);
            assertThat(response.amountByYearAndCategory().get("2030")).containsEntry("屋根", 5_000_000L);
            assertThat(response.amountByYearAndCategory()).containsKey("2031");
            assertThat(response.amountByYearAndCategory().get("2031")).containsEntry("外壁", 8_000_000L);

            // totalByYear の確認（2030年: 1000万+500万=1500万）
            assertThat(response.totalByYear()).containsEntry("2030", 15_000_000L);
            assertThat(response.totalByYear()).containsEntry("2031", 8_000_000L);

            // categories の確認（TreeSet による自然順ソート済み）
            assertThat(response.categories()).containsExactlyInAnyOrder("屋根", "外壁");
            assertThat(response.categories()).isSortedAccordingTo(String::compareTo);
        }
    }

    // ========================================
    // CPI トレンド
    // ========================================

    @Nested
    @DisplayName("CPI トレンド計算")
    class CpiTrend {

        @Test
        @DisplayName("CPI が 2024 年で 100.0 を返す")
        void CPI_2024年_100() {
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    anyString(), anyLong()))
                    .willReturn(List.of());

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2024, 2025);

            assertThat(response.cpiTrendByYear()).containsKey("2024");
            assertThat(response.cpiTrendByYear().get("2024")).isEqualTo(100.0);
        }

        @Test
        @DisplayName("CPI が 2025 年で 101.5 を返す")
        void CPI_2025年_101_5() {
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    anyString(), anyLong()))
                    .willReturn(List.of());

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2024, 2025);

            assertThat(response.cpiTrendByYear()).containsKey("2025");
            assertThat(response.cpiTrendByYear().get("2025")).isEqualTo(101.5);
        }
    }

    // ========================================
    // 理事長解決
    // ========================================

    @Nested
    @DisplayName("理事長解決")
    class ChairpersonResolution {

        @Test
        @DisplayName("理事長が在任期間内で正しく解決される")
        void 理事長が在任期間内で解決される() {
            TeamMemberTerm term = buildTerm(1L, "理事長",
                    LocalDate.of(2029, 4, 1), LocalDate.of(2031, 3, 31));
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    eq(SCOPE_TYPE), eq(SCOPE_ID)))
                    .willReturn(List.of(term));
            UserEntity user = buildUser(1L, "田中 太郎");
            given(userRepository.findAllById(anyIterable()))
                    .willReturn(List.of(user));

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2029, 2031);

            // 2029〜2031 は在任期間内
            assertThat(response.chairpersonByYear()).containsEntry("2029", "田中 太郎");
            assertThat(response.chairpersonByYear()).containsEntry("2030", "田中 太郎");
            assertThat(response.chairpersonByYear()).containsEntry("2031", "田中 太郎");
        }

        @Test
        @DisplayName("該当年度に理事長がいない場合は chairpersonByYear に含まれない")
        void 理事長がいない年度は含まれない() {
            // 理事長は 2028 年まで在任
            TeamMemberTerm term = buildTerm(1L, "理事長",
                    LocalDate.of(2026, 4, 1), LocalDate.of(2028, 3, 31));
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    eq(SCOPE_TYPE), eq(SCOPE_ID)))
                    .willReturn(List.of(term));
            UserEntity user = buildUser(1L, "鈴木 花子");
            given(userRepository.findAllById(anyIterable()))
                    .willReturn(List.of(user));

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2029, 2030);

            // 2029〜2030 は在任期間外
            assertThat(response.chairpersonByYear()).doesNotContainKey("2029");
            assertThat(response.chairpersonByYear()).doesNotContainKey("2030");
        }
    }

    // ========================================
    // 年度範囲補正
    // ========================================

    @Nested
    @DisplayName("年度範囲の補正")
    class YearRangeCorrection {

        @Test
        @DisplayName("yearFrom > yearTo の場合は from = to - 1 に補正される")
        void yearFromがyearToより大きい場合に補正される() {
            given(repairPlanItemRepository.aggregateByYearAndCategory(
                    anyString(), anyLong(), anyInt(), anyInt()))
                    .willReturn(List.of());
            given(teamMemberTermRepository.findByScopeTypeAndScopeIdOrderByTermStartAsc(
                    anyString(), anyLong()))
                    .willReturn(List.of());

            RepairPlanTimelineResponse response =
                    timelineService.getTimeline(SCOPE_TYPE, SCOPE_ID, 2035, 2030);

            // to=2030, from は 2030-1=2029 に補正される
            assertThat(response.yearFrom()).isEqualTo(2029);
            assertThat(response.yearTo()).isEqualTo(2030);
        }
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    private TeamMemberTerm buildTerm(Long userId, String roleLabel,
                                     LocalDate termStart, LocalDate termEnd) {
        return TeamMemberTerm.builder()
                .organizationId(10L)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .userId(userId)
                .roleLabel(roleLabel)
                .termStart(termStart)
                .termEnd(termEnd)
                .isActive(true)
                .build();
    }

    /**
     * テスト用 UserEntity を Reflection で構築する。
     * UserEntity は AllArgsConstructor(PRIVATE) のため Builder 経由でのみ生成可能。
     * ただし displayName のみ設定できれば十分なのでモックを使う。
     */
    private UserEntity buildUser(Long id, String displayName) {
        UserEntity user = org.mockito.Mockito.mock(UserEntity.class);
        given(user.getId()).willReturn(id);
        given(user.getDisplayName()).willReturn(displayName);
        return user;
    }
}
