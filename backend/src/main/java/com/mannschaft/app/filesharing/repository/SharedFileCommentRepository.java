package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileCommentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ファイルコメントリポジトリ。
 */
public interface SharedFileCommentRepository extends JpaRepository<SharedFileCommentEntity, Long> {

    /**
     * ファイルのコメント一覧を取得する。
     */
    List<SharedFileCommentEntity> findByFileIdOrderByCreatedAtAsc(Long fileId);

    /**
     * ファイルのコメント数を取得する。
     */
    long countByFileId(Long fileId);
}
