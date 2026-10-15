package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 市 Phase2 D: 複数地域募集（N:N）の地域中間表リポジトリ。
 *
 * <p>主キーは UUIDv7（{@link RecruitmentListingRegionEntity} は {@code UuidV7Entity} 継承。
 * バックフィル行のみ UUID v4）。札→地域一覧の解決・再設定（replace）で使用する。</p>
 */
public interface RecruitmentListingRegionRepository
        extends JpaRepository<RecruitmentListingRegionEntity, UUID> {

    /**
     * 札に紐づく全地域を取得する（詳細レスポンス・代表地域の決定）。
     * 先頭（代表）の決定は呼び出し側で安定ソートする想定（id 昇順）。
     *
     * @param listingId 札ID
     * @return 地域リスト（id 昇順）
     */
    List<RecruitmentListingRegionEntity> findByListingIdOrderByIdAsc(Long listingId);

    /**
     * ページ内の複数札に紐づく地域をバルク取得する（N+1 回避・レスポンス enrich）。
     *
     * @param listingIds 札ID集合
     * @return 地域リスト（listing_id 昇順 → id 昇順）
     */
    List<RecruitmentListingRegionEntity> findByListingIdInOrderByListingIdAscIdAsc(
            Collection<Long> listingIds);

    /**
     * 札の地域を全削除する（地域再設定時に使用・replace パターン）。
     *
     * @param listingId 札ID
     * @return 削除件数
     */
    @Modifying
    int deleteByListingId(Long listingId);
}
