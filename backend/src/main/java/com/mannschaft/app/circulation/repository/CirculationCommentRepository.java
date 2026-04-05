package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 回覧コメントリポジトリ。
 */
public interface CirculationCommentRepository extends JpaRepository<CirculationCommentEntity, Long> {

    /**
     * 文書IDでコメントをページング取得する。
     */
    Page<CirculationCommentEntity> findByDocumentIdOrderByCreatedAtAsc(Long documentId, Pageable pageable);

    /**
     * IDと文書IDでコメントを取得する。
     */
    Optional<CirculationCommentEntity> findByIdAndDocumentId(Long id, Long documentId);

    /**
     * 文書IDでコメント数を取得する。
     */
    long countByDocumentId(Long documentId);
}
