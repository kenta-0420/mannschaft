package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.ArchivedMembershipGrantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * アーカイブ在籍への移行時に取り上げた役職・権限グループ付与の退避記録リポジトリ。
 *
 * <p>所有ドメインは role（§5.3.1）。書き手・読み手とも {@code RoleService} 経由に限定し、
 * 他ドメインからこの Repository を直接触らせない。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.3.1</p>
 */
public interface ArchivedMembershipGrantRepository extends JpaRepository<ArchivedMembershipGrantEntity, UUID> {

    /**
     * 復元対象（未復元・現行世代）の退避記録を取得する。
     * §9.4.1.1: 過去周期の保留権限を復活させないため archive_generation で絞り込む。
     */
    List<ArchivedMembershipGrantEntity> findByMembershipIdAndArchiveGenerationAndRestoredAtIsNull(
            Long membershipId, Integer archiveGeneration);
}
