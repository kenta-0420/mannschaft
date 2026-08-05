package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdCampaignDeliveryClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 Phase 11-c キャンペーン配信 claim リポジトリ。
 *
 * <p>claim の確保自体は {@code save}（{@code (campaign_id, user_id, week_start)} の一意制約違反を
 * {@link com.mannschaft.app.advertising.campaign.service.AdCampaignDeliveryClaimService} が
 * {@code DataIntegrityViolationException} として捕捉する形）で行う。本インターフェースは
 * 解放（削除）と、候補ユーザーからの除外に使う既 claim 済み user_id 一覧の取得を提供する。</p>
 */
public interface AdCampaignDeliveryClaimRepository extends JpaRepository<AdCampaignDeliveryClaim, UUID> {

    /**
     * 全チャネル skip で実配信が 0 件だった場合の claim 解放。
     * {@code (campaign_id, user_id, week_start)} は一意なので高々 1 行を削除する。
     */
    long deleteByCampaignIdAndUserIdAndWeekStart(UUID campaignId, Long userId, LocalDate weekStart);

    /**
     * 指定キャンペーンにつき、{@code weekStart} が {@code rangeStart}〜{@code rangeEnd}（両端含む）の
     * 範囲にある claim 済み user_id 一覧を返す。
     *
     * <p>週開始はユーザー TZ 依存のため、ワーカー側で候補一覧からの除外に使う際は
     * 想定され得る全ユーザー TZ をカバーする範囲（例: 直近1週間強）を渡すこと。
     * 範囲を広めに取っても安全側（過剰除外は次回以降に再試行されるだけで、二重配信の原因にはならない）。</p>
     */
    @Query("SELECT c.userId FROM AdCampaignDeliveryClaim c "
            + "WHERE c.campaignId = :campaignId AND c.weekStart BETWEEN :rangeStart AND :rangeEnd")
    List<Long> findClaimedUserIds(@Param("campaignId") UUID campaignId,
                                   @Param("rangeStart") LocalDate rangeStart,
                                   @Param("rangeEnd") LocalDate rangeEnd);
}
