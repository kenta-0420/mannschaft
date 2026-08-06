package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdCampaignDeliveryClaim;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F09.17 Phase 11-c キャンペーン配信 claim リポジトリ。
 *
 * <p>claim の確保は {@link #tryClaim} の {@code INSERT IGNORE}（MySQL 方言）で行う。
 * 影響行数 1 が「確保できた」、0 が「既に他が確保済み」を意味する。</p>
 *
 * <h3>なぜ例外捕捉（{@code save} + {@code DataIntegrityViolationException}）ではなく INSERT IGNORE か</h3>
 * <p>一意制約違反が起きた時点で Spring はそのトランザクションを rollback-only に印付ける。
 * {@code REQUIRES_NEW} の内側で例外を捕まえて正常 return しても、トランザクション終了時の
 * コミットで {@code UnexpectedRollbackException} が飛ぶ（Spring の仕様どおりの挙動であり、
 * 捕捉側からは「握り潰したのに死ぬ」ように見える）。{@code INSERT IGNORE} は制約違反そのものを
 * 例外化しないため、この破綻が原理的に起こらない。</p>
 */
public interface AdCampaignDeliveryClaimRepository extends JpaRepository<AdCampaignDeliveryClaim, UUID> {

    /**
     * {@code (campaign_id, user_id, week_start)} の claim を確保しようと試みる。
     *
     * <p>{@code created_at} は本番 DDL には {@code DEFAULT CURRENT_TIMESTAMP} があるが、
     * test プロファイルのスキーマは Hibernate {@code ddl-auto=create} が Entity 定義から
     * 生成するため DB 側デフォルトを持たない（{@code @Column} の Java 側属性だけでは DDL に
     * 反映されない）。DEFAULT に依存すると test では非厳格モードで zero-date
     * （{@code 0000-00-00 00:00:00}）が入り、読み出し時に
     * {@code JpaSystemException: Zero date value prohibited} で落ちる（結合テストで実際に再現）。
     * 環境差に依存しないよう、常に呼び出し側で生成した現在時刻を明示的にバインドする。</p>
     *
     * @param id         事前生成した UUID（{@link com.mannschaft.app.common.entity.UuidV7Entity} と
     *                   同じ採番機構で生成すること。ネイティブ INSERT は JPA の
     *                   {@code @GeneratedValue} ライフサイクルを経由しないため、呼び出し側で採番する）
     * @param createdAt  呼び出し側で確定させた作成時刻（DB 側 DEFAULT には依存しない）
     * @return INSERT できた行数（0 または 1）。0 は「既に他の実行が確保済み」を意味する。
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO ad_campaign_delivery_claims "
            + "(id, campaign_id, user_id, week_start, created_at) "
            + "VALUES (:id, :campaignId, :userId, :weekStart, :createdAt)", nativeQuery = true)
    int tryClaim(@Param("id") UUID id,
                 @Param("campaignId") UUID campaignId,
                 @Param("userId") Long userId,
                 @Param("weekStart") LocalDate weekStart,
                 @Param("createdAt") LocalDateTime createdAt);

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
