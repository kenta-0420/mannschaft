package com.mannschaft.app.advertising.ranking.service;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.ranking.EquipmentRankingErrorCode;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingExclusionEntity;
import com.mannschaft.app.advertising.ranking.entity.ExclusionType;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingExclusionRepository;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingRepository;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 同類チーム備品ランキングサービス。
 * ランキング取得・opt-out操作・SYSTEM_ADMIN向け管理操作を提供する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EquipmentRankingService {

    private final EquipmentRankingRepository rankingRepository;
    private final EquipmentRankingExclusionRepository exclusionRepository;
    private final AffiliateConfigRepository affiliateConfigRepository;
    private final TeamRepository teamRepository;
    private final FeatureFlagService featureFlagService;

    @Value("${equipment.ranking.min-team-count:5}")
    private int minTeamCount;

    /**
     * 同類チームの備品ランキングを取得する。
     *
     * @param teamId     チームID（同じtemplateのランキングを返す）
     * @param category   カテゴリフィルタ（null = 全カテゴリ横断）
     * @param limit      返却件数（デフォルト10、最大20）
     * @param linkedOnly true の場合 ASIN あり（Amazon リンクあり）のみ返却
     * @return ランキングデータ
     */
    @Cacheable(value = "equipment:trending", key = "#teamId + ':' + #category + ':' + #linkedOnly")
    public EquipmentTrendingResult getTrending(Long teamId, String category, int limit, boolean linkedOnly) {
        // フィーチャーフラグチェック
        if (!featureFlagService.isEnabled("FEATURE_V9_ENABLED")
                || !featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")) {
            return EquipmentTrendingResult.empty();
        }

        // チームのtemplateを取得
        String template = teamRepository.findById(teamId)
                .map(team -> team.getTemplate())
                .orElse(null);

        if (template == null) {
            return EquipmentTrendingResult.empty();
        }

        // opt-out 状態確認
        boolean isOptOut = exclusionRepository.existsByTeamIdAndExclusionType(
                teamId, ExclusionType.TEAM_OPT_OUT);

        // ランキングデータ取得
        String cacheCategory = category != null ? category : "__ALL__";
        List<EquipmentRankingEntity> rankings = rankingRepository
                .findByTeamTemplateAndCategoryOrderByRankAsc(template, cacheCategory);

        Optional<LocalDateTime> lastCalculated = rankingRepository.findLastCalculatedAt();
        if (lastCalculated.isEmpty() && rankings.isEmpty()) {
            throw new BusinessException(EquipmentRankingErrorCode.RANKING_NOT_READY);
        }

        // Amazon アフィリエイトタグ
        String amazonTag = affiliateConfigRepository
                .findActiveAmazonConfig(LocalDateTime.now(ZoneId.of("Asia/Tokyo")))
                .map(AffiliateConfigEntity::getTagId)
                .orElse(null);

        // フィルタ適用（team_count >= minTeamCount、linkedOnly）
        List<EquipmentRankingEntity> filtered = rankings.stream()
                .filter(r -> r.getTeamCount() >= minTeamCount)
                .filter(r -> !linkedOnly || r.getAmazonAsin() != null)
                .limit(limit)
                .toList();

        // 同テンプレートのチーム数（opt-out 数を引く）
        long totalTemplateTeams = teamRepository.countByTemplate(template)
                - exclusionRepository.countByExclusionType(ExclusionType.TEAM_OPT_OUT);

        return new EquipmentTrendingResult(
                template,
                category,
                isOptOut,
                filtered,
                lastCalculated.orElse(null),
                amazonTag,
                totalTemplateTeams);
    }

    /**
     * チームのランキングデータ提供をopt-outする。
     *
     * @param teamId     opt-outするチームID
     * @param operatorId 操作者ユーザーID
     */
    @Transactional
    @CacheEvict(value = "equipment:trending", allEntries = true)
    public void optOut(Long teamId, Long operatorId) {
        if (exclusionRepository.existsByTeamIdAndExclusionType(teamId, ExclusionType.TEAM_OPT_OUT)) {
            throw new BusinessException(EquipmentRankingErrorCode.ALREADY_OPT_OUT);
        }
        EquipmentRankingExclusionEntity entity = EquipmentRankingExclusionEntity.builder()
                .exclusionType(ExclusionType.TEAM_OPT_OUT)
                .teamId(teamId)
                .excludedByUserId(operatorId)
                .build();
        exclusionRepository.save(entity);
        log.info("備品ランキングopt-out: teamId={}, operatorId={}", teamId, operatorId);
    }

    /**
     * チームのopt-outを解除する（データ提供を再開）。
     *
     * @param teamId 解除するチームID
     */
    @Transactional
    @CacheEvict(value = "equipment:trending", allEntries = true)
    public void optIn(Long teamId) {
        EquipmentRankingExclusionEntity entity = exclusionRepository
                .findByTeamIdAndExclusionType(teamId, ExclusionType.TEAM_OPT_OUT)
                .orElseThrow(() -> new BusinessException(EquipmentRankingErrorCode.OPT_OUT_NOT_FOUND));
        exclusionRepository.delete(entity);
        log.info("備品ランキングopt-in（解除）: teamId={}", teamId);
    }

    /**
     * SYSTEM_ADMIN: ランキング集計統計を取得する。
     */
    public RankingStatsResult getStats() {
        long totalItems = rankingRepository.countByTeamCountGreaterThanEqual(minTeamCount);
        Optional<LocalDateTime> lastCalculated = rankingRepository.findLastCalculatedAt();
        List<String> templates = rankingRepository.findDistinctTeamTemplates();
        int optOutTeamCount = exclusionRepository.countByExclusionType(ExclusionType.TEAM_OPT_OUT);
        int excludedItemCount = exclusionRepository.countByExclusionType(ExclusionType.ITEM_EXCLUSION);
        long itemsBelowThreshold = rankingRepository.countByTeamCountLessThan(minTeamCount);
        long itemsWithAsin = rankingRepository.countByAmazonAsinIsNotNull();
        return new RankingStatsResult(
                totalItems,
                lastCalculated.orElse(null),
                templates,
                optOutTeamCount,
                excludedItemCount,
                itemsBelowThreshold,
                itemsWithAsin,
                minTeamCount);
    }

    /**
     * SYSTEM_ADMIN: 特定アイテムをランキングから除外する。
     *
     * @param normalizedName 除外対象の正規化済み備品名
     * @param reason         除外理由
     * @param adminUserId    操作者ユーザーID
     * @return 作成した除外設定エンティティ
     */
    @Transactional
    public EquipmentRankingExclusionEntity addItemExclusion(
            String normalizedName, String reason, Long adminUserId) {
        // 同一 normalized_name の重複チェック
        boolean duplicate = exclusionRepository.findExcludedNormalizedNames()
                .contains(normalizedName);
        if (duplicate) {
            throw new BusinessException(EquipmentRankingErrorCode.DUPLICATE_EXCLUSION);
        }
        EquipmentRankingExclusionEntity entity = EquipmentRankingExclusionEntity.builder()
                .exclusionType(ExclusionType.ITEM_EXCLUSION)
                .normalizedName(normalizedName)
                .reason(reason)
                .excludedByUserId(adminUserId)
                .build();
        return exclusionRepository.save(entity);
    }

    /**
     * SYSTEM_ADMIN: 除外設定を解除する。
     *
     * @param exclusionId 除外設定ID
     */
    @Transactional
    public void removeItemExclusion(Long exclusionId) {
        EquipmentRankingExclusionEntity entity = exclusionRepository.findById(exclusionId)
                .orElseThrow(() -> new BusinessException(EquipmentRankingErrorCode.EXCLUSION_NOT_FOUND));
        exclusionRepository.delete(entity);
    }

    /**
     * SYSTEM_ADMIN: 除外設定一覧を取得する。
     *
     * @return 除外設定リスト（作成日時降順）
     */
    public List<EquipmentRankingExclusionEntity> getAllExclusions() {
        return exclusionRepository.findAllByOrderByCreatedAtDesc();
    }

    // ---- 結果 DTO 相当（Controller に渡す） ----

    public record EquipmentTrendingResult(
            String teamTemplate,
            String category,
            boolean optOut,
            List<EquipmentRankingEntity> ranking,
            LocalDateTime calculatedAt,
            String amazonTag,
            long totalTemplatesTeams) {

        static EquipmentTrendingResult empty() {
            return new EquipmentTrendingResult(null, null, false, List.of(), null, null, 0L);
        }
    }

    public record RankingStatsResult(
            long totalVisibleItems,
            LocalDateTime lastCalculatedAt,
            List<String> availableTemplates,
            int optOutTeamCount,
            int excludedItemCount,
            long itemsBelowThreshold,
            long itemsWithAsin,
            int minTeamCountThreshold) {}
}
