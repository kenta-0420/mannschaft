package com.mannschaft.app.advertising.ranking.service;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.ranking.entity.EquipmentRankingEntity;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingExclusionRepository;
import com.mannschaft.app.advertising.ranking.repository.EquipmentRankingRepository;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 同類チーム備品ランキング日次集計バッチ。
 * 毎日 AM 3:00 (JST) に全チームの備品データを集計し、equipment_rankings テーブルを再構築する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EquipmentRankingBatchService {

    private final EquipmentItemRepository equipmentItemRepository;
    private final EquipmentAssignmentRepository equipmentAssignmentRepository;
    private final EquipmentRankingRepository equipmentRankingRepository;
    private final EquipmentRankingExclusionRepository exclusionRepository;
    private final AffiliateConfigRepository affiliateConfigRepository;
    private final TeamRepository teamRepository;
    private final EquipmentNormalizationService normalizationService;
    private final FeatureFlagService featureFlagService;

    @Value("${equipment.ranking.min-team-count:5}")
    private int minTeamCount;

    @Value("${equipment.ranking.score-weight-team:3.0}")
    private double scoreWeightTeam;

    @Value("${equipment.ranking.score-weight-consume:1.0}")
    private double scoreWeightConsume;

    @Value("${equipment.ranking.top-n-per-category:20}")
    private int topNPerCategory;

    /**
     * 日次ランキング集計バッチ。毎日 AM 3:00 (JST) に実行。
     * ShedLock による排他制御あり（複数インスタンス環境でも1回のみ実行保証）。
     */
    @Scheduled(cron = "${equipment.ranking.cron:0 0 3 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "equipmentRankingBatch", lockAtMostFor = "15m", lockAtLeastFor = "1m")
    public void execute() {
        if (!featureFlagService.isEnabled("FEATURE_V9_ENABLED")
                || !featureFlagService.isEnabled("FEATURE_EQUIPMENT_RANKING_ENABLED")) {
            log.info("備品ランキングバッチ: フィーチャーフラグ無効のためスキップ");
            return;
        }
        runBatch();
    }

    /**
     * バッチ本体（手動起動 API からも呼ばれる）。
     */
    @CacheEvict(value = "equipment:trending", allEntries = true)
    @Transactional
    public void runBatch() {
        long startMs = System.currentTimeMillis();
        LocalDateTime startedAt = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        log.info("備品ランキングバッチ開始: {}", startedAt);

        // 1. opt-out チームID・除外アイテム名を取得
        List<Long> optOutTeamIds = exclusionRepository.findOptOutTeamIds();
        List<String> excludedNames = exclusionRepository.findExcludedNormalizedNames();

        // 2. Amazon アフィリエイトタグを取得（存在しない場合は null）
        String amazonTag = affiliateConfigRepository
                .findActiveAmazonConfig(startedAt)
                .map(AffiliateConfigEntity::getTagId)
                .orElse(null);

        // 3. チームIDとtemplateのマッピングを一括取得
        Map<Long, String> teamTemplateMap = buildTeamTemplateMap();

        // 4. equipment_items を集計（opt-out チーム除外）
        // JPQL の IN 句は空リストを受け取れないため、空の場合は番兵値 -1L を渡す
        // findAllForRankingBatch の JPQL で size()=0 の場合は除外しない設計
        List<Long> optOutIds = optOutTeamIds.isEmpty() ? List.of(-1L) : optOutTeamIds;
        List<EquipmentItemEntity> items = equipmentItemRepository.findAllForRankingBatch(optOutIds);

        // 5. 消費イベント集計（過去90日）
        LocalDateTime since = startedAt.minusDays(90);
        List<Object[]> consumeRows = equipmentAssignmentRepository.countConsumeEventsByItemId(
                since, optOutIds);
        Map<Long, Long> consumeCountByItemId = consumeRows.stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]));

        // 6. template × normalized_name でグループ化・集計
        Map<String, Map<String, GroupAgg>> templateGroupMap = new HashMap<>();

        for (EquipmentItemEntity item : items) {
            if (item.getTeamId() == null) continue;
            String template = teamTemplateMap.get(item.getTeamId());
            if (template == null) continue;

            String normalizedName = normalizationService.normalize(item.getName());
            if (normalizedName.isBlank()) continue;

            Map<String, GroupAgg> nameMap = templateGroupMap
                    .computeIfAbsent(template, k -> new HashMap<>());
            GroupAgg agg = nameMap.computeIfAbsent(normalizedName, k -> new GroupAgg());

            agg.teamIds.add(item.getTeamId());
            agg.totalQuantity += (item.getQuantity() != null ? item.getQuantity() : 0);
            // カテゴリ: 最頻出のカテゴリを採用
            if (item.getCategory() != null) {
                agg.categoryFreq.merge(item.getCategory(), 1, Integer::sum);
            }
            // ASIN: 最頻出のASINを採用
            if (item.getAmazonAsin() != null) {
                agg.asinFreq.merge(item.getAmazonAsin(), 1, Integer::sum);
            }
            // item_name: 最頻出の名称を採用
            agg.nameFreq.merge(item.getName(), 1, Integer::sum);
            // 消費イベント: equipment_item 単位の消費数を加算
            agg.consumeEventCount += consumeCountByItemId.getOrDefault(item.getId(), 0L);
        }

        // 7. ランキングデータを構築
        LocalDateTime calculatedAt = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        List<EquipmentRankingEntity> rankings = new ArrayList<>();

        for (Map.Entry<String, Map<String, GroupAgg>> templateEntry : templateGroupMap.entrySet()) {
            String template = templateEntry.getKey();
            Map<String, GroupAgg> nameMap = templateEntry.getValue();

            // カテゴリ別グループ化（各 normalizedName → 最頻出カテゴリで振り分け）
            Map<String, List<Map.Entry<String, GroupAgg>>> byCategory = nameMap.entrySet().stream()
                    .collect(Collectors.groupingBy(e -> {
                        GroupAgg agg = e.getValue();
                        if (agg.categoryFreq.isEmpty()) return "__UNCATEGORIZED__";
                        return agg.categoryFreq.entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse("__UNCATEGORIZED__");
                    }));

            // カテゴリ別ランキング
            for (Map.Entry<String, List<Map.Entry<String, GroupAgg>>> catEntry : byCategory.entrySet()) {
                String category = catEntry.getKey().equals("__UNCATEGORIZED__") ? "__ALL__" : catEntry.getKey();
                rankings.addAll(buildRankingRows(
                        template, category, catEntry.getValue(), excludedNames, calculatedAt));
            }

            // 全カテゴリ横断ランキング（category = '__ALL__'）
            rankings.addAll(buildRankingRows(
                    template, "__ALL__", new ArrayList<>(nameMap.entrySet()), excludedNames, calculatedAt));
        }

        // 8. 全件削除 → 一括 INSERT
        equipmentRankingRepository.deleteAll();
        equipmentRankingRepository.saveAll(rankings);

        log.info("備品ランキングバッチ完了: 所要時間={}ms, 総レコード数={}, template数={}",
                System.currentTimeMillis() - startMs,
                rankings.size(),
                templateGroupMap.size());
    }

    /**
     * 指定 template・category のランキング行リストを構築する。
     *
     * @param template     チームテンプレート
     * @param category     カテゴリ（"__ALL__" は全カテゴリ横断）
     * @param entries      [normalizedName, GroupAgg] のエントリリスト
     * @param excludedNames 除外対象の正規化済み備品名リスト
     * @param calculatedAt 集計日時
     * @return ランキングエンティティリスト
     */
    private List<EquipmentRankingEntity> buildRankingRows(
            String template,
            String category,
            List<Map.Entry<String, GroupAgg>> entries,
            List<String> excludedNames,
            LocalDateTime calculatedAt) {

        List<EquipmentRankingEntity> result = new ArrayList<>();
        short rank = 1;

        // 除外フィルタ適用 → スコア降順でソート → 上位N件
        List<Map.Entry<String, GroupAgg>> sorted = entries.stream()
                .filter(e -> !excludedNames.contains(e.getKey()))
                .sorted(Comparator.comparingDouble(
                        (Map.Entry<String, GroupAgg> e) -> calcScore(e.getValue())).reversed())
                .limit(topNPerCategory)
                .toList();

        for (Map.Entry<String, GroupAgg> entry : sorted) {
            String normalizedName = entry.getKey();
            GroupAgg agg = entry.getValue();

            int teamCount = agg.teamIds.size();
            // 最頻出の item_name
            String itemName = agg.nameFreq.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(normalizedName);

            // ASIN 信頼度計算
            String resolvedAsin = null;
            Short confidence = null;
            if (!agg.asinFreq.isEmpty()) {
                int totalAsinCount = agg.asinFreq.values().stream().mapToInt(Integer::intValue).sum();
                Map.Entry<String, Integer> topAsin = agg.asinFreq.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .orElse(null);
                if (topAsin != null) {
                    int pct = (int) Math.round((double) topAsin.getValue() / totalAsinCount * 100);
                    confidence = (short) pct;
                    if (pct >= 50) {
                        resolvedAsin = topAsin.getKey();
                    }
                }
            }

            result.add(EquipmentRankingEntity.builder()
                    .teamTemplate(template)
                    .category(category)
                    .rank(rank)
                    .itemName(itemName)
                    .normalizedName(normalizedName)
                    .amazonAsin(resolvedAsin)
                    .asinConfidence(confidence)
                    .teamCount(teamCount)
                    .totalQuantity(agg.totalQuantity)
                    .consumeEventCount((int) agg.consumeEventCount)
                    .score(BigDecimal.valueOf(calcScore(agg)))
                    .calculatedAt(calculatedAt)
                    .build());
            rank++;
        }
        return result;
    }

    private double calcScore(GroupAgg agg) {
        return agg.teamIds.size() * scoreWeightTeam + agg.consumeEventCount * scoreWeightConsume;
    }

    /**
     * チームIDとtemplateのマッピングを一括取得。
     * 全チームをメモリに乗せる（チーム数が多い場合はページング対応を検討）。
     */
    private Map<Long, String> buildTeamTemplateMap() {
        return teamRepository.findAll().stream()
                .filter(t -> t.getTemplate() != null)
                .collect(Collectors.toMap(
                        TeamEntity::getId,
                        TeamEntity::getTemplate));
    }

    /** グループ集計用ローカルクラス */
    private static class GroupAgg {
        final Set<Long> teamIds = new HashSet<>();
        int totalQuantity = 0;
        long consumeEventCount = 0;
        final Map<String, Integer> categoryFreq = new HashMap<>();
        final Map<String, Integer> asinFreq = new HashMap<>();
        final Map<String, Integer> nameFreq = new HashMap<>();
    }
}
