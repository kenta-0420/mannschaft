package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F09.17 メッセージ型キャンペーン本体リポジトリ。
 *
 * <p>F09.17 Phase 11-d-1 で {@code AbstractTenantAwareRepository} 継承を解除し、
 * {@code JpaRepository} 直接継承に変更した。
 * チーム単位キャンペーンを運用可能にするため scope_type + scope_id 2 カラム方式に移行し、
 * テナント分離キーが {@code organization_id} 単独ではなくなったため。</p>
 *
 * <p>互換性保持: 既存 Service 層が依存している
 * {@code findByOrganizationIdAndDeletedAtIsNull}, {@code findByIdAndOrganizationIdAndDeletedAtIsNull}
 * は本インターフェースで明示宣言して保持する。
 * Service 層の scope 化完了時 (Phase 11-d-2 以降) に削除予定。</p>
 */
public interface AdMessagingCampaignRepository
        extends JpaRepository<AdMessagingCampaign, UUID> {

    // ====== 互換性保持メソッド群（Phase 11-d-2 で削除予定） ======

    /**
     * @deprecated F09.17 Phase 11-d-1 で {@link #findByScopeTypeAndScopeIdAndDeletedAtIsNull} を導入。
     *             Phase 11-d-2 以降で削除予定。互換維持のため一時保持。
     */
    @Deprecated
    Page<AdMessagingCampaign> findByOrganizationIdAndDeletedAtIsNull(Long organizationId, Pageable pageable);

    /**
     * @deprecated F09.17 Phase 11-d-1 で
     *             {@link #findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull} を導入。
     *             Phase 11-d-2 以降で削除予定。互換維持のため一時保持。
     */
    @Deprecated
    Optional<AdMessagingCampaign> findByIdAndOrganizationIdAndDeletedAtIsNull(UUID id, Long organizationId);

    // ====== scope ベース新規メソッド ======

    /**
     * F09.17 Phase 11-d-1: スコープ単位の論理削除されていないキャンペーン一覧をページング取得する。
     */
    Page<AdMessagingCampaign> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * F09.17 Phase 11-d-1: スコープ単位の論理削除されていないキャンペーン一覧を取得する。
     */
    List<AdMessagingCampaign> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId);

    /**
     * F09.17 Phase 11-d-1: スコープに属する論理削除されていない単一キャンペーンを取得する。
     */
    Optional<AdMessagingCampaign> findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(
            UUID id, ScopeType scopeType, Long scopeId);

    /**
     * F09.17 Phase 11-d-1: スコープに属する論理削除されていないキャンペーン件数を返す。
     */
    long countByScopeTypeAndScopeIdAndDeletedAtIsNull(ScopeType scopeType, Long scopeId);

    // ====== 既存メソッド ======

    /** 広告主アカウント単位の一覧 (DRAFT・REVIEW など全状態)。 */
    List<AdMessagingCampaign> findByAdvertiserAccountIdAndDeletedAtIsNull(Long advertiserAccountId);

    /** 配信スケジューラ用: 状態 + ウィンドウで配信対象キャンペーンを探索。 */
    List<AdMessagingCampaign> findByStatusAndStartsAtLessThanEqualAndEndsAtGreaterThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime startsAtUpper, LocalDateTime endsAtLower);

    /** モデレーションキュー用: 審査状態順の一覧。 */
    List<AdMessagingCampaign> findByModerationStatusAndDeletedAtIsNullOrderByCreatedAtAsc(
            AdModerationStatus moderationStatus);

    /**
     * SYSTEM_ADMIN 審査キュー用: 指定の {@code moderation_status} 群に該当する論理削除されていない
     * キャンペーンをページング+作成日時昇順で取得する。
     *
     * <p>F09.17 Phase 11-a: 通常 {@code PENDING / AUTO_FLAGGED} を渡す想定。</p>
     */
    Page<AdMessagingCampaign> findByModerationStatusInAndDeletedAtIsNull(
            Collection<AdModerationStatus> moderationStatuses, Pageable pageable);

    /**
     * F09.17 Phase 11-b ε-A 自動遷移ワーカー用: 指定状態かつ {@code starts_at <= now} のキャンペーンを取得。
     *
     * <p>{@link AdCampaignStatus#SCHEDULED} を渡し、配信開始時刻に到達したキャンペーンを
     * {@code DELIVERING} へ自動遷移するために使う。</p>
     */
    List<AdMessagingCampaign> findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime now);

    /**
     * F09.17 Phase 11-b ε-A 自動遷移ワーカー用: 指定状態かつ {@code ends_at <= now} のキャンペーンを取得。
     *
     * <p>{@link AdCampaignStatus#DELIVERING} を渡し、配信終了時刻に到達したキャンペーンを
     * {@code COMPLETED} へ自動遷移するために使う。</p>
     */
    List<AdMessagingCampaign> findByStatusAndEndsAtLessThanEqualAndDeletedAtIsNull(
            AdCampaignStatus status, LocalDateTime now);

    /**
     * F09.17 Phase 11-b ε-B 配信ワーカー用: 配信中（ウィンドウ内）の対象キャンペーンを
     * 悲観ロック付きで取得する。
     *
     * <p>配信ワーカーが 1 分間隔で起動するため、複数ノードで同時にスキャンする場合の
     * 重複配信を {@code SELECT ... FOR UPDATE} で防ぐ。
     * {@code @SchedulerLock} はトップレベル多重実行を防ぐが、念のため二重防御とする。</p>
     *
     * @param status DELIVERING を渡す
     * @param now    現在時刻（starts_at &lt;= now AND ends_at &gt;= now の判定基準）
     * @return ロック取得済みキャンペーン一覧
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM AdMessagingCampaign c "
            + "WHERE c.status = :status "
            + "AND c.startsAt <= :now "
            + "AND c.endsAt >= :now "
            + "AND c.deletedAt IS NULL")
    List<AdMessagingCampaign> findActiveDeliveringForUpdate(
            @Param("status") AdCampaignStatus status,
            @Param("now") LocalDateTime now);
}
