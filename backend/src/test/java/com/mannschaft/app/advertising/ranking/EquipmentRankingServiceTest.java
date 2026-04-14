package com.mannschaft.app.advertising.ranking;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingExclusionEntity;
import com.mannschaft.app.advertising.ranking.entity.ExclusionType;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingExclusionRepository;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingRepository;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.EquipmentTrendingResult;
import com.mannschaft.app.advertising.ranking.service.EquipmentRankingService.RankingStatsResult;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EquipmentRankingService} の単体テスト。
 * ランキング取得・opt-out操作・アイテム除外管理の業務ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EquipmentRankingService 単体テスト")
class EquipmentRankingServiceTest {

    @Mock private EquipmentRankingRepository rankingRepository;
    @Mock private EquipmentRankingExclusionRepository exclusionRepository;
    @Mock private AffiliateConfigRepository affiliateConfigRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private FeatureFlagService featureFlagService;

    @InjectMocks
    private EquipmentRankingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR_ID = 1L;
    private static final String TEMPLATE = "soccer_youth";

    /** テスト用ランキングエンティティを生成する */
    private EquipmentRankingEntity createRankingEntity(int rank, int teamCount, String asin) {
        return EquipmentRankingEntity.builder()
                .teamTemplate(TEMPLATE)
                .category("ボール")
                .rank((short) rank)
                .itemName("サッカーボール")
                .normalizedName("サッカーボール")
                .amazonAsin(asin)
                .teamCount(teamCount)
                .totalQuantity(50)
                .consumeEventCount(10)
                .score(BigDecimal.valueOf(20.0))
                .calculatedAt(LocalDateTime.now())
                .build();
    }

    /** テスト用チームエンティティを生成する */
    private TeamEntity createTeam() {
        TeamEntity team = TeamEntity.builder()
                .name("テストチーム")
                .template(TEMPLATE)
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(false)
                .build();
        // BaseEntity の id は @GeneratedValue のため ReflectionTestUtils で設定
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        return team;
    }

    @Nested
    @DisplayName("getTrending")
    class GetTrending {

        @Test
        @DisplayName("正常系: ランキングデータが返る（minTeamCount 以上のみ）")
        void ランキングデータが返る_minTeamCount以上のみ() {
            // Given
            given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(true);
            given(featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(createTeam()));
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(false);

            // minTeamCount=5 → teamCount=3のものはフィルタされる
            EquipmentRankingEntity above = createRankingEntity(1, 10, null);
            EquipmentRankingEntity below = createRankingEntity(2, 3, null);
            given(rankingRepository.findByTeamTemplateAndCategoryOrderByRankAsc(eq(TEMPLATE), any()))
                    .willReturn(List.of(above, below));
            given(rankingRepository.findLastCalculatedAt())
                    .willReturn(Optional.of(LocalDateTime.now()));
            given(affiliateConfigRepository.findActiveAmazonConfig(any()))
                    .willReturn(Optional.empty());
            given(teamRepository.countByTemplate(TEMPLATE)).willReturn(20L);
            given(exclusionRepository.countByExclusionType(ExclusionType.TEAM_OPT_OUT)).willReturn(0);

            // When
            EquipmentTrendingResult result = service.getTrending(TEAM_ID, null, 10, false);

            // Then
            assertThat(result.ranking()).hasSize(1);
            assertThat(result.ranking().get(0).getTeamCount()).isEqualTo(10);
            assertThat(result.optOut()).isFalse();
        }

        @Test
        @DisplayName("正常系: linkedOnly=true の場合 ASIN ありのみ返る")
        void linkedOnly_trueの場合ASINありのみ返る() {
            // Given
            given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(true);
            given(featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(createTeam()));
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(false);

            EquipmentRankingEntity withAsin = createRankingEntity(1, 10, "B08XXXXX");
            EquipmentRankingEntity withoutAsin = createRankingEntity(2, 10, null);
            given(rankingRepository.findByTeamTemplateAndCategoryOrderByRankAsc(eq(TEMPLATE), any()))
                    .willReturn(List.of(withAsin, withoutAsin));
            given(rankingRepository.findLastCalculatedAt())
                    .willReturn(Optional.of(LocalDateTime.now()));
            given(affiliateConfigRepository.findActiveAmazonConfig(any()))
                    .willReturn(Optional.empty());
            given(teamRepository.countByTemplate(TEMPLATE)).willReturn(20L);
            given(exclusionRepository.countByExclusionType(ExclusionType.TEAM_OPT_OUT)).willReturn(0);

            // When
            EquipmentTrendingResult result = service.getTrending(TEAM_ID, null, 10, true);

            // Then
            assertThat(result.ranking()).hasSize(1);
            assertThat(result.ranking().get(0).getAmazonAsin()).isEqualTo("B08XXXXX");
        }

        @Test
        @DisplayName("異常系: ランキングデータ未集計（rankings空・lastCalculatedAt空）は RANKING_NOT_READY 例外")
        void ランキング未集計でRANKING_NOT_READY例外() {
            // Given
            given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(true);
            given(featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(createTeam()));
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(false);
            given(rankingRepository.findByTeamTemplateAndCategoryOrderByRankAsc(any(), any()))
                    .willReturn(List.of());
            given(rankingRepository.findLastCalculatedAt()).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getTrending(TEAM_ID, null, 10, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_001"));
        }

        @Test
        @DisplayName("正常系: opt-out 状態が結果に含まれる")
        void optOut状態が結果に含まれる() {
            // Given
            given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(true);
            given(featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")).willReturn(true);
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(createTeam()));
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(true);

            EquipmentRankingEntity entity = createRankingEntity(1, 10, null);
            given(rankingRepository.findByTeamTemplateAndCategoryOrderByRankAsc(eq(TEMPLATE), any()))
                    .willReturn(List.of(entity));
            given(rankingRepository.findLastCalculatedAt())
                    .willReturn(Optional.of(LocalDateTime.now()));
            given(affiliateConfigRepository.findActiveAmazonConfig(any()))
                    .willReturn(Optional.empty());
            given(teamRepository.countByTemplate(TEMPLATE)).willReturn(20L);
            given(exclusionRepository.countByExclusionType(ExclusionType.TEAM_OPT_OUT)).willReturn(1);

            // When
            EquipmentTrendingResult result = service.getTrending(TEAM_ID, null, 10, false);

            // Then
            assertThat(result.optOut()).isTrue();
        }

        @Test
        @DisplayName("正常系: フィーチャーフラグ無効時は empty() が返る")
        void フィーチャーフラグ無効時はemptyが返る() {
            // Given
            given(featureFlagService.isEnabled("FEATURE_V9_ENABLED")).willReturn(false);

            // When
            EquipmentTrendingResult result = service.getTrending(TEAM_ID, null, 10, false);

            // Then
            assertThat(result.ranking()).isEmpty();
            assertThat(result.teamTemplate()).isNull();
        }
    }

    @Nested
    @DisplayName("optOut")
    class OptOut {

        @Test
        @DisplayName("正常系: opt-out 設定が保存される")
        void optOut設定が保存される() {
            // Given
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(false);
            given(exclusionRepository.save(any(EquipmentRankingExclusionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            service.optOut(TEAM_ID, OPERATOR_ID);

            // Then
            then(exclusionRepository).should().save(any(EquipmentRankingExclusionEntity.class));
        }

        @Test
        @DisplayName("異常系: 既に opt-out 済みは ALREADY_OPT_OUT 例外")
        void 既にoptOut済みはALREADY_OPT_OUT例外() {
            // Given
            given(exclusionRepository.existsByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.optOut(TEAM_ID, OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_002"));
        }
    }

    @Nested
    @DisplayName("optIn")
    class OptIn {

        @Test
        @DisplayName("正常系: opt-out 設定が削除される")
        void optOut設定が削除される() {
            // Given
            EquipmentRankingExclusionEntity entity = EquipmentRankingExclusionEntity.builder()
                    .exclusionType(ExclusionType.TEAM_OPT_OUT)
                    .teamId(TEAM_ID)
                    .excludedByUserId(OPERATOR_ID)
                    .build();
            given(exclusionRepository.findByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(Optional.of(entity));

            // When
            service.optIn(TEAM_ID);

            // Then
            then(exclusionRepository).should().delete(entity);
        }

        @Test
        @DisplayName("異常系: opt-out 未設定で OPT_OUT_NOT_FOUND 例外")
        void optOut未設定でOPT_OUT_NOT_FOUND例外() {
            // Given
            given(exclusionRepository.findByTeamIdAndExclusionType(TEAM_ID, ExclusionType.TEAM_OPT_OUT))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.optIn(TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_003"));
        }
    }

    @Nested
    @DisplayName("addItemExclusion")
    class AddItemExclusion {

        @Test
        @DisplayName("正常系: 除外設定が保存される")
        void 除外設定が保存される() {
            // Given
            String normalizedName = "サッカーボール";
            String reason = "スパム的な備品名";
            given(exclusionRepository.findExcludedNormalizedNames()).willReturn(List.of());
            EquipmentRankingExclusionEntity saved = EquipmentRankingExclusionEntity.builder()
                    .exclusionType(ExclusionType.ITEM_EXCLUSION)
                    .normalizedName(normalizedName)
                    .reason(reason)
                    .excludedByUserId(OPERATOR_ID)
                    .build();
            given(exclusionRepository.save(any(EquipmentRankingExclusionEntity.class))).willReturn(saved);

            // When
            EquipmentRankingExclusionEntity result =
                    service.addItemExclusion(normalizedName, reason, OPERATOR_ID);

            // Then
            assertThat(result.getNormalizedName()).isEqualTo(normalizedName);
            assertThat(result.getExclusionType()).isEqualTo(ExclusionType.ITEM_EXCLUSION);
            then(exclusionRepository).should().save(any(EquipmentRankingExclusionEntity.class));
        }

        @Test
        @DisplayName("異常系: 同一 normalizedName が既に存在する場合は DUPLICATE_EXCLUSION 例外")
        void 同一normalizedNameが存在する場合はDUPLICATE_EXCLUSION例外() {
            // Given
            String normalizedName = "サッカーボール";
            given(exclusionRepository.findExcludedNormalizedNames()).willReturn(List.of(normalizedName));

            // When / Then
            assertThatThrownBy(() -> service.addItemExclusion(normalizedName, "理由", OPERATOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_006"));
        }
    }

    @Nested
    @DisplayName("removeItemExclusion")
    class RemoveItemExclusion {

        @Test
        @DisplayName("正常系: 除外設定が削除される")
        void 除外設定が削除される() {
            // Given
            Long exclusionId = 100L;
            EquipmentRankingExclusionEntity entity = EquipmentRankingExclusionEntity.builder()
                    .exclusionType(ExclusionType.ITEM_EXCLUSION)
                    .normalizedName("サッカーボール")
                    .build();
            given(exclusionRepository.findById(exclusionId)).willReturn(Optional.of(entity));

            // When
            service.removeItemExclusion(exclusionId);

            // Then
            then(exclusionRepository).should().delete(entity);
        }

        @Test
        @DisplayName("異常系: 除外設定IDが不在の場合は EXCLUSION_NOT_FOUND 例外")
        void 除外設定IDが不在の場合はEXCLUSION_NOT_FOUND例外() {
            // Given
            Long exclusionId = 999L;
            given(exclusionRepository.findById(exclusionId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.removeItemExclusion(exclusionId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ERANK_004"));
        }
    }
}
