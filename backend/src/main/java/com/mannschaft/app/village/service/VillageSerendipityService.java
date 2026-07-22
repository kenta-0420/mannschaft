package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageSerendipityRankingResponse;
import com.mannschaft.app.village.dto.VillageSerendipityScoreResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageSerendipityScoreEntity;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSerendipityScoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコアサービス。
 *
 * <p>村人同士の出会い頻度・交流度を集計するスコアの読み書きを担う。
 * 書き込みは日次バッチ {@link com.mannschaft.app.village.batch.VillageSerendipityBatchService}
 * から呼び出され、読み取りは {@link com.mannschaft.app.village.controller.VillageSerendipityController}
 * から呼び出される。</p>
 *
 * <h2>アーキテクチャ原則</h2>
 * <ul>
 *   <li>原則1: {@code userId} に FK は張らず、Service 層では存在検証も行わない（バッチで集計済みのもののみ存在）</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageSerendipityService {

    /** ランキングの最大ページサイズ。 */
    private static final int MAX_LIMIT = 100;

    /** ランキングのデフォルトサイズ。 */
    private static final int DEFAULT_LIMIT = 10;

    private final VillageSerendipityScoreRepository serendipityRepository;
    private final VillageRepository villageRepository;

    // ====================================================================
    // 読み取り API
    // ====================================================================

    /**
     * 指定ユーザーの自分のスコアを取得する。
     *
     * <p>レコードが存在しない場合は {@link VillageErrorCode#SERENDIPITY_NOT_FOUND} を投げる
     * （初回バッチ実行前は 404、UI 側で「まだスコアがありません」と案内する想定）。</p>
     */
    public VillageSerendipityScoreResponse getMyScore(UUID villageId, Long userId) {
        loadActiveVillage(villageId);
        VillageSerendipityScoreEntity entity = serendipityRepository
                .findByVillageIdAndUserId(villageId, userId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.SERENDIPITY_NOT_FOUND));
        Integer rank = computeRank(villageId, entity);
        return VillageSerendipityScoreResponse.of(entity, rank);
    }

    /**
     * ご縁スコアランキング（上位 N 件）を返す。
     *
     * @param villageId 村 ID
     * @param limit     上位件数（1〜100、超過時はクリップ）
     * @deprecated F17.2 §8.2 により表示廃止（相性表示へ置換）。撤去は次リリース。集計自体は推薦の内部信号として存置。
     */
    @Deprecated(since = "F17.2", forRemoval = true)
    public VillageSerendipityRankingResponse getRanking(UUID villageId, Integer limit) {
        loadActiveVillage(villageId);
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Pageable pageable = PageRequest.of(0, size);
        Page<VillageSerendipityScoreEntity> page =
                serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(villageId, pageable);
        long total = serendipityRepository.countByVillageId(villageId);

        List<VillageSerendipityScoreResponse> items = new ArrayList<>(page.getNumberOfElements());
        int idx = 1;
        for (VillageSerendipityScoreEntity entity : page.getContent()) {
            items.add(VillageSerendipityScoreResponse.of(entity, idx));
            idx++;
        }
        return new VillageSerendipityRankingResponse(items, total);
    }

    // ====================================================================
    // 書き込み API（バッチ専用）
    // ====================================================================

    /**
     * 指定ユーザーのスコアを加算する（存在しなければ新規作成）。
     *
     * <p>日次バッチから呼び出される想定で、{@code encounterIncrement} と
     * {@code scoreIncrement} を加算的に積み上げる。CAS / @Version での競合は
     * バッチが単一実行（ShedLock）のため発生しない前提だが、JPA @Version は維持する。</p>
     *
     * @param villageId          村 ID
     * @param userId             ユーザー ID
     * @param encounterIncrement 出会い回数の増分（>= 0）
     * @param scoreIncrement     交流スコアの増分（>= 0）
     */
    @Transactional
    public void updateUserScore(UUID villageId,
                                Long userId,
                                long encounterIncrement,
                                long scoreIncrement) {
        if (encounterIncrement < 0 || scoreIncrement < 0) {
            throw new IllegalArgumentException("increments must be >= 0");
        }
        if (encounterIncrement == 0 && scoreIncrement == 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        VillageSerendipityScoreEntity entity = serendipityRepository
                .findByVillageIdAndUserId(villageId, userId)
                .orElseGet(() -> VillageSerendipityScoreEntity.builder()
                        .villageId(villageId)
                        .userId(userId)
                        .encounterCount(0L)
                        .interactionScore(0L)
                        .lastUpdatedAt(now)
                        .build());

        entity.setEncounterCount(entity.getEncounterCount() + encounterIncrement);
        entity.setInteractionScore(entity.getInteractionScore() + scoreIncrement);
        entity.setLastUpdatedAt(now);
        serendipityRepository.save(entity);
    }

    // ====================================================================
    // 共通ヘルパ
    // ====================================================================

    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return v;
    }

    /**
     * 指定スコアの順位を算出する（interactionScore より高いレコード数 + 1）。
     *
     * <p>同点者は同順位とする（=競合する WHERE 句なしの単純 COUNT）。
     * 大規模村ではコストが高いため、Phase 4 で materialized rank 化を検討する。</p>
     */
    private Integer computeRank(UUID villageId, VillageSerendipityScoreEntity entity) {
        // TODO Phase 4: 専用 COUNT クエリ or materialized rank に置換して効率化
        Pageable allPage = PageRequest.of(0, Integer.MAX_VALUE);
        Page<VillageSerendipityScoreEntity> page =
                serendipityRepository.findByVillageIdOrderByInteractionScoreDesc(villageId, allPage);
        int rank = 1;
        for (VillageSerendipityScoreEntity e : page.getContent()) {
            if (e.getInteractionScore() > entity.getInteractionScore()) {
                rank++;
            } else {
                break;
            }
        }
        return rank;
    }
}
