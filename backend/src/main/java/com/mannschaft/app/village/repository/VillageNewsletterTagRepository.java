package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ニュースレタータグマスタ リポジトリ（F17.1 ②-1・案Y の pull 層）。
 */
public interface VillageNewsletterTagRepository
        extends JpaRepository<VillageNewsletterTagEntity, UUID> {

    /** 村のタグ一覧（表示順・論理削除除外）。 */
    List<VillageNewsletterTagEntity> findByVillageIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID villageId);

    /** 村内タグ名の重複判定（partial unique は MySQL 不可のため Service 層で使う）。 */
    Optional<VillageNewsletterTagEntity> findByVillageIdAndNameAndDeletedAtIsNull(UUID villageId, String name);

    /** 村内の1タグ（論理削除除外）。 */
    Optional<VillageNewsletterTagEntity> findByIdAndVillageIdAndDeletedAtIsNull(UUID id, UUID villageId);

    /**
     * 複数タグを ID 一括で取得する（一覧の N+1 回避・②-4 堅牢性 AC-9）。論理削除タグは除外する。
     *
     * <p>一覧ページ内の全号が参照するタグ ID をまとめて渡し、タグマスタを 1 クエリで引く。
     * 空集合は呼び出し側で短絡し、{@code IN ()} を発行しない。</p>
     */
    List<VillageNewsletterTagEntity> findByIdInAndDeletedAtIsNull(Collection<UUID> ids);
}
