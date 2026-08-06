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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
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

    /** 空カテゴリ集合時に JPQL の {@code IN ()} 空リストエラーを避けるためのダミー値（絞り込み条件は無効化される）。 */
    private static final List<String> NO_CATEGORY_FILTER = List.of("__NONE__");

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

        // 3) 候補選定: 削除/凍結/UNLISTED 除外、未参加、未ピン、カテゴリ一致を SQL 側の WHERE で絞り込む。
        //    旧実装は findAll() で全村をロードしユーザーごとにアプリ側フィルタしていた（ユーザー数 × 村数のオーダー）。
        //    ORDER BY RAND() は全行に乱数を振ってからソートするため村テーブルが大きくなるほど致命的に遅く
        //    インデックスも効かないので使わず、WHERE 句で絞り込んだ候補 ID（件数は限られる）だけを取得し
        //    アプリ側で Random により 1 件選ぶ（候補数ぶんのメモリしか使わない）。
        Set<UUID> excludeIds = new HashSet<>();
        excludeIds.addAll(joinedVillageIds);
        excludeIds.addAll(pinnedVillageIds);

        List<UUID> candidateIds = villageRepository.findPilgrimageCandidateIds(
                VillageVisibility.PUBLIC,
                excludeIds,
                categories.isEmpty(),
                categories.isEmpty() ? NO_CATEGORY_FILTER : categories);

        if (candidateIds.isEmpty()) {
            return false;
        }

        // 4) アプリ側でランダム選定（本格化は将来の TODO）
        UUID pickedId = candidateIds.get(ThreadLocalRandom.current().nextInt(candidateIds.size()));
        VillageEntity picked = villageRepository.findById(pickedId).orElse(null);
        if (picked == null) {
            // 取得直後に削除等が発生した稀なレース。今回は推薦を作らずスキップ（次回バッチで再試行）
            return false;
        }

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
