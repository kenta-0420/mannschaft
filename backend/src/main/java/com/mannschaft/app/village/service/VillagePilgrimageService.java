package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PilgrimageRecommendationResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillagePilgrimageRecommendationEntity;
import com.mannschaft.app.village.repository.VillagePilgrimageRecommendationRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — 巡礼サービス。
 *
 * <p>「おすすめ村ローテーション」を担う。日次バッチが
 * {@link com.mannschaft.app.village.batch.VillagePilgrimageBatchService} で
 * {@code (user_id, today)} の単位で推薦を 1 件生成し、本 Service はその参照・訪問記録・履歴取得を担当する。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: user_id は ID のみ保持、FK は張らない。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillagePilgrimageService {

    /** 履歴取得の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;
    /** 履歴取得のデフォルトページサイズ。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final VillagePilgrimageRecommendationRepository pilgrimageRepository;
    private final VillageRepository villageRepository;
    private final AuditLogService auditLogService;

    // ====================================================================
    // 今日の推薦
    // ====================================================================

    /**
     * 今日（実行時のローカル日付）の推薦を 1 件取得する。
     *
     * @param userId 対象ユーザー
     * @return 推薦が無ければ {@link Optional#empty()}。バッチ未実行・対象村なしの両方ありうる。
     */
    public Optional<PilgrimageRecommendationResponse> getTodaysRecommendation(Long userId) {
        LocalDate today = LocalDate.now();
        return pilgrimageRepository.findByUserIdAndRecommendedDate(userId, today)
                .map(rec -> PilgrimageRecommendationResponse.of(rec, loadVillageOrNull(rec.getRecommendedVillageId())));
    }

    // ====================================================================
    // 訪問記録
    // ====================================================================

    /**
     * 推薦村への訪問を記録する（{@code visited_at} をセット）。
     *
     * <ul>
     *   <li>本人の推薦でない / 存在しない → {@link VillageErrorCode#PILGRIMAGE_NOT_FOUND}（404、IDOR 防止）</li>
     *   <li>既に訪問済みの場合は冪等で no-op（{@code visited_at} は最初の訪問日時を残す）</li>
     * </ul>
     */
    @Transactional
    public PilgrimageRecommendationResponse recordVisit(Long userId, UUID recommendationId) {
        VillagePilgrimageRecommendationEntity entity = pilgrimageRepository.findById(recommendationId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.PILGRIMAGE_NOT_FOUND));
        if (!entity.getUserId().equals(userId)) {
            // IDOR 対策: 本人以外には 404 で隠す
            throw new BusinessException(VillageErrorCode.PILGRIMAGE_NOT_FOUND);
        }

        if (entity.getVisitedAt() == null) {
            entity.setVisitedAt(LocalDateTime.now());
            pilgrimageRepository.save(entity);

            auditLogService.record(
                    AuditEventType.VILLAGE_PILGRIMAGE_VISITED.name(),
                    userId, null, null, null,
                    null, null, null,
                    "{\"recommendationId\":\"" + entity.getId()
                            + "\",\"villageId\":\"" + entity.getRecommendedVillageId()
                            + "\",\"recommendedDate\":\"" + entity.getRecommendedDate() + "\"}"
            );
            log.info("巡礼訪問記録: userId={} villageId={} recommendationId={}",
                    userId, entity.getRecommendedVillageId(), entity.getId());
        }

        return PilgrimageRecommendationResponse.of(entity, loadVillageOrNull(entity.getRecommendedVillageId()));
    }

    // ====================================================================
    // 履歴
    // ====================================================================

    /**
     * 自分の巡礼推薦履歴を取得する（推薦日降順）。
     */
    public List<PilgrimageRecommendationResponse> listMyHistory(Long userId, Pageable pageable) {
        Pageable resolved = resolvePageable(pageable);
        Page<VillagePilgrimageRecommendationEntity> page =
                pilgrimageRepository.findByUserIdOrderByRecommendedDateDesc(userId, resolved);
        if (page.isEmpty()) {
            return List.of();
        }

        // N+1 を避けるため村の ID 集合をまとめて引く
        List<UUID> villageIds = page.getContent().stream()
                .map(VillagePilgrimageRecommendationEntity::getRecommendedVillageId)
                .distinct()
                .toList();
        Map<UUID, VillageEntity> villageMap = new HashMap<>();
        for (VillageEntity v : villageRepository.findAllById(villageIds)) {
            villageMap.put(v.getId(), v);
        }

        return page.getContent().stream()
                .map(rec -> PilgrimageRecommendationResponse.of(rec, villageMap.get(rec.getRecommendedVillageId())))
                .toList();
    }

    // ====================================================================
    // 共通ヘルパ
    // ====================================================================

    private VillageEntity loadVillageOrNull(UUID villageId) {
        return villageRepository.findById(villageId).orElse(null);
    }

    private Pageable resolvePageable(Pageable pageable) {
        if (pageable == null) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE);
        }
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }
}
