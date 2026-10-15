package com.mannschaft.app.errorreport.repository;

import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F12.5 Phase 2 — エラーレポート操作履歴・コメントのリポジトリ。
 */
public interface ErrorReportActivityRepository
        extends JpaRepository<ErrorReportActivityEntity, Long> {

    /**
     * 指定エラーレポートの操作履歴を新しい順にページング取得する。
     */
    Page<ErrorReportActivityEntity> findByErrorReportIdOrderByCreatedAtDesc(
            Long errorReportId, Pageable pageable);
}
