package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdCampaignDeliveryClaim;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignDeliveryClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F09.17 Phase 11-c キャンペーン配信 claim-then-act サービス。
 *
 * <p>{@code ad_campaign_delivery_claims} の {@code (campaign_id, user_id, week_start)} 一意制約を
 * 「先に場所を取ってから配る」の根拠とする。{@code BlogMediaService#cleanupOrphanMedia} が
 * 条件付き DELETE の影響行数で「行を確保できたか」を判定する作法に倣い、本サービスは
 * INSERT の成否（一意制約違反の有無）で「claim を確保できたか」を判定する。</p>
 *
 * <h3>なぜ例外捕捉で判定するか</h3>
 * <p>claim 対象は Hibernate 管理エンティティであり、DB ネイティブの {@code INSERT IGNORE} を
 * 使うより、JPA の一意制約違反（{@link DataIntegrityViolationException}）を
 * {@link Propagation#REQUIRES_NEW} の専用トランザクションで捕捉するほうが UUID/日付の型変換を
 * Hibernate に一任でき安全。REQUIRES_NEW のため、衝突時にこの小さなトランザクションだけが
 * ロールバックし、呼び出し元（{@link AdCampaignDeliveryDispatcher} の user 単位トランザクション）を
 * 巻き込まない。{@code saveAndFlush} で即座に DB へ反映させ、一意制約違反をこの場で確定させる。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdCampaignDeliveryClaimService {

    private final AdCampaignDeliveryClaimRepository claimRepository;

    /**
     * (campaignId, userId, weekStart) の claim を確保しようと試みる。
     *
     * @return 確保できた場合 true。既に他の実行が確保済みの場合 false（例外は投げない）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(UUID campaignId, Long userId, LocalDate weekStart) {
        if (campaignId == null || userId == null || weekStart == null) {
            throw new IllegalArgumentException("campaignId, userId, weekStart は必須です");
        }
        try {
            AdCampaignDeliveryClaim claim = AdCampaignDeliveryClaim.builder()
                    .campaignId(campaignId)
                    .userId(userId)
                    .weekStart(weekStart)
                    .build();
            claimRepository.saveAndFlush(claim);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.debug("AD_DELIVERY_CLAIM_CONFLICT campaignId={} userId={} weekStart={}",
                    campaignId, userId, weekStart);
            return false;
        }
    }

    /**
     * 全チャネル skip（実配信 0 件）だった場合に claim を解放する。
     * FreqCap の {@link AdFrequencyCapService#releaseSlot} とセットで呼ぶこと。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(UUID campaignId, Long userId, LocalDate weekStart) {
        long deleted = claimRepository.deleteByCampaignIdAndUserIdAndWeekStart(campaignId, userId, weekStart);
        log.debug("AD_DELIVERY_CLAIM_RELEASED campaignId={} userId={} weekStart={} deleted={}",
                campaignId, userId, weekStart, deleted);
    }

    /**
     * 候補ユーザー一覧から既 claim 済みユーザーを除外するための集合を返す。
     *
     * <p>週開始はユーザー TZ 依存で単一の値に定まらないため、{@code today} 基準で
     * ±8 日程度の広めの範囲（{@link #CLAIM_LOOKUP_MARGIN_DAYS}）を対象にする。
     * 過剰除外は「次回実行まで再試行が遅れる」だけで、狭すぎる範囲による取りこぼしと違い
     * 二重配信を起こさないため安全側に倒す。</p>
     */
    @Transactional(readOnly = true)
    public Set<Long> findClaimedUserIds(UUID campaignId, LocalDate today) {
        LocalDate rangeStart = today.minusDays(CLAIM_LOOKUP_MARGIN_DAYS);
        LocalDate rangeEnd = today.plusDays(1);
        return new HashSet<>(claimRepository.findClaimedUserIds(campaignId, rangeStart, rangeEnd));
    }

    /** 週境界がユーザー TZ 依存であることを踏まえた、claim 検索の安全マージン日数。 */
    static final int CLAIM_LOOKUP_MARGIN_DAYS = 8;
}
