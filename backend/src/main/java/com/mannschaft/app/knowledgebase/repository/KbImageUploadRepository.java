package com.mannschaft.app.knowledgebase.repository;

import com.mannschaft.app.knowledgebase.entity.KbImageUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * ナレッジベース画像アップロードリポジトリ。
 */
public interface KbImageUploadRepository extends JpaRepository<KbImageUploadEntity, Long> {

    /**
     * 未紐付けかつ指定日時以前にアップロードされた孤立画像を取得する（クリーンアップ用）。
     */
    List<KbImageUploadEntity> findByKbPageIdIsNullAndCreatedAtBefore(LocalDateTime threshold);

    /**
     * S3キーで画像アップロードを取得する。
     */
    Optional<KbImageUploadEntity> findByS3Key(String s3Key);
}
