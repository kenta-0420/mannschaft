package com.mannschaft.app.incident.repository;

import com.mannschaft.app.incident.entity.IncidentCommentAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * インシデントコメント添付ファイルリポジトリ。
 */
public interface IncidentCommentAttachmentRepository extends JpaRepository<IncidentCommentAttachmentEntity, Long> {

    /**
     * コメント ID に紐づく添付ファイル一覧を取得する。
     */
    List<IncidentCommentAttachmentEntity> findByCommentId(Long commentId);

    /**
     * コメント ID に紐づく添付ファイル数を返す。
     */
    int countByCommentId(Long commentId);
}
