package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.QuickMemoAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * ポイっとメモ添付ファイルリポジトリ。
 */
public interface QuickMemoAttachmentRepository extends JpaRepository<QuickMemoAttachmentEntity, Long> {

    /**
     * メモに紐付く添付ファイル一覧をソート順で取得する。
     */
    List<QuickMemoAttachmentEntity> findByMemoIdOrderBySortOrderAsc(Long memoId);

    /**
     * S3キーで添付ファイルを取得する。
     */
    Optional<QuickMemoAttachmentEntity> findByS3Key(String s3Key);

    /**
     * メモに紐付く添付ファイル件数を取得する。
     */
    long countByMemoId(Long memoId);

    /**
     * S3キーで添付ファイルを削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM QuickMemoAttachmentEntity a WHERE a.s3Key = :s3Key")
    void deleteByS3Key(@Param("s3Key") String s3Key);

    /**
     * メモIDに紐付く添付ファイルをすべて削除する（物理削除バッチ用の明示的削除）。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM QuickMemoAttachmentEntity a WHERE a.memoId = :memoId")
    void deleteByMemoId(@Param("memoId") Long memoId);

    /**
     * 複数メモのS3キー一覧を取得する（物理削除バッチ用）。
     */
    @Query("SELECT a.s3Key FROM QuickMemoAttachmentEntity a WHERE a.memoId IN :memoIds")
    List<String> findS3KeysByMemoIdIn(@Param("memoIds") List<Long> memoIds);
}
