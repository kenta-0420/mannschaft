package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 回覧添付ファイルリポジトリ。
 */
public interface CirculationAttachmentRepository extends JpaRepository<CirculationAttachmentEntity, Long> {

    /**
     * 文書IDで添付ファイル一覧を取得する。
     */
    List<CirculationAttachmentEntity> findByDocumentIdOrderByCreatedAtAsc(Long documentId);

    /**
     * IDと文書IDで添付ファイルを取得する。
     */
    Optional<CirculationAttachmentEntity> findByIdAndDocumentId(Long id, Long documentId);

    /**
     * 文書IDで添付ファイルを全削除する。
     */
    void deleteAllByDocumentId(Long documentId);

    /**
     * 文書IDで添付ファイル数を取得する。
     */
    long countByDocumentId(Long documentId);
}
