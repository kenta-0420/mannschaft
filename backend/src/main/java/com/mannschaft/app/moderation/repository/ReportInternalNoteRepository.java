package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 通報内部メモリポジトリ。
 */
public interface ReportInternalNoteRepository extends JpaRepository<ReportInternalNoteEntity, Long> {

    /**
     * 通報IDで内部メモ一覧を取得する。
     */
    List<ReportInternalNoteEntity> findByReportIdOrderByCreatedAtAsc(Long reportId);
}
