package com.mannschaft.app.moderation.repository;

import com.mannschaft.app.moderation.entity.ReportActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 通報対応アクションリポジトリ。
 */
public interface ReportActionRepository extends JpaRepository<ReportActionEntity, Long> {

    /**
     * 通報IDに紐づくアクション一覧を取得する。
     */
    List<ReportActionEntity> findByReportIdOrderByCreatedAtDesc(Long reportId);

    /**
     * 対応者のアクション履歴を取得する。
     */
    List<ReportActionEntity> findByActionByOrderByCreatedAtDesc(Long actionBy);

    /**
     * 通報IDに紐づくアクション数を取得する。
     */
    long countByReportId(Long reportId);
}
