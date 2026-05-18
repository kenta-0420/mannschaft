package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillagePilgrimageRecommendationRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F17.1 Phase 3-β — 巡礼（おすすめ村ローテーション）日次バッチ。
 *
 * <p>毎日 09:00 JST に、村に所属している全ユーザーに対して 1 件の推薦を生成する。
 * 推薦先は次の条件で選定する（ルールベース）:</p>
 *
 * <ol>
 *   <li>そのユーザーが所属している村のカテゴリと一致する村</li>
 *   <li>本人が未参加</li>
 *   <li>本人が未ピン留め</li>
 *   <li>削除/凍結されていない、かつ {@code visibility = PUBLIC} の村のみ
 *       （UNLISTED は招待制を尊重し巡礼対象から除外）</li>
 * </ol>
 *
 * <p>候補が複数ある場合はランダム選定。候補がゼロなら推薦行は作らない（無理に作っても価値が薄いため）。</p>
 *
 * <p>TODO（本格化）: 興味嗜好スコア・接続グラフ・タイムゾーン考慮を Phase 3 後半以降で導入予定。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則5: 個別ユーザー分の {@link #generateForUser(Long, LocalDate)} を
 *       {@code @Transactional} に閉じる。バッチ全体は無トランザクション。</li>
 *   <li>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</li>
 *   <li>1 ユーザー失敗しても次のユーザーは続行する。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillagePilgrimageBatchService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserVillagePinRepository pinRepository;
    private final VillagePilgrimageRecommendationRepository pilgrimageRepository;

    private final Random random = new SecureRandom();

    /**
     * 毎日 09:00 JST にバッチ実行。
     *
     * <p>cron 表現 {@code "0 0 9 * * *"} は JST 09:00 ちょうどに発火。
     * 朝のログイン時に「今日の村」を提示できるタイミングを優先した。</p>
     */
    @BatchEndpoint(name = "village-pilgrimage-daily", description = "村の巡礼推薦を毎日 09:00 にユーザー別に生成する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "villagePilgrimageBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        LocalDate today = LocalDate.now();
        log.info("巡礼推薦バッチ開始: date={}", today);

        List<Long> userIds = membershipRepository.findDistinctActiveUserSubjectIds();
        int generated = 0;
        int skipped = 0;
        int failed = 0;

        for (Long userId : userIds) {
            try {
                boolean created = generateForUser(userId, today);
                if (created) {
                    generated++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.error("巡礼推薦生成失敗: userId={}", userId, e);
            }
        }

        log.info("巡礼推薦バッチ完了: 対象ユーザー数={} 生成={} スキップ={} 失敗={}",
                userIds.size(), generated, skipped, failed);
    }

    /**
     * 個別ユーザーの推薦行を 1 件生成する。既に当日推薦が存在する場合 / 候補ゼロの場合は何もしない。
     *
     * @return 行が新規生成されたら {@code true}、スキップなら {@code false}
     */
    @Transactional
    public boolean generateForUser(Long userId, LocalDate date) {
        // 冪等性: 当日既に推薦行が存在するならスキップ
        if (pilgrimageRepository.existsByUserIdAndRecommendedDate(userId, date)) {
            return false;
        }

        // 1) 自分の所属村とカテゴリ集合を抽出
        List<VillageMembershipEntity> memberships = membershipRepository.findActiveUserMemberships(userId);
        if (memberships.isEmpty()) {
            return false;
        }
        Set<UUID> joinedVillageIds = memberships.stream()
                .map(VillageMembershipEntity::getVillageId)
                .collect(Collectors.toSet());
        List<VillageEntity> joinedVillages = villageRepository.findAllById(joinedVillageIds);
        Set<String> categories = joinedVillages.stream()
                .map(VillageEntity::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toSet());

        // 2) 未ピン村集合を抽出
        Set<UUID> pinnedVillageIds = pinRepository.findByUserIdOrderBySortOrderAsc(userId).stream()
                .map(UserVillagePinEntity::getVillageId)
                .collect(Collectors.toSet());

        // 3) 候補プール作成: 削除/凍結/UNLISTED 除外、未参加、未ピン、カテゴリ一致
        //    findAll を使うのは Phase 3-β の最小実装ゆえ。村数が増えたら category インデックスでの絞込クエリに置換予定。
        List<VillageEntity> allVillages = villageRepository.findAll();
        List<VillageEntity> candidates = new ArrayList<>();
        Set<UUID> excludeIds = new HashSet<>();
        excludeIds.addAll(joinedVillageIds);
        excludeIds.addAll(pinnedVillageIds);

        for (VillageEntity v : allVillages) {
            if (v.getDeletedAt() != null || v.getArchivedAt() != null) {
                continue;
            }
            if (v.getVisibility() != VillageVisibility.PUBLIC) {
                continue;
            }
            if (excludeIds.contains(v.getId())) {
                continue;
            }
            if (!categories.isEmpty() && (v.getCategory() == null || !categories.contains(v.getCategory()))) {
                continue;
            }
            candidates.add(v);
        }

        if (candidates.isEmpty()) {
            return false;
        }

        // 4) ランダム選定（本格化は将来の TODO）
        VillageEntity picked = candidates.get(random.nextInt(candidates.size()));

        String reason;
        if (!categories.isEmpty() && categories.contains(picked.getCategory())) {
            reason = "CATEGORY_MATCH:" + picked.getCategory();
        } else {
            reason = "RANDOM";
        }
        // reason は VARCHAR(100) 制限
        if (reason.length() > 100) {
            reason = reason.substring(0, 100);
        }

        VillagePilgrimageRecommendationEntity entity = VillagePilgrimageRecommendationEntity.builder()
                .userId(userId)
                .recommendedVillageId(picked.getId())
                .recommendedDate(date)
                .reason(reason)
                .build();
        pilgrimageRepository.save(entity);

        log.debug("巡礼推薦生成: userId={} villageId={} reason={}", userId, picked.getId(), reason);
        return true;
    }
}
