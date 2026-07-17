package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
