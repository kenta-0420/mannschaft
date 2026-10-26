package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 掲示板添付ファイルリポジトリ。
 */
public interface BulletinAttachmentRepository extends JpaRepository<BulletinAttachmentEntity, Long> {

    /**
     * ターゲットの添付ファイル一覧を取得する。
     */
    List<BulletinAttachmentEntity> findByTargetTypeAndTargetIdOrderByCreatedAtAsc(
            TargetType targetType, Long targetId);

    /**
     * ターゲットの添付ファイルを一括削除する。
     */
    void deleteByTargetTypeAndTargetId(TargetType targetType, Long targetId);
}
