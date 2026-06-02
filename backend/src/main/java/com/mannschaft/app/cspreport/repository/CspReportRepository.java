package com.mannschaft.app.cspreport.repository;

import com.mannschaft.app.cspreport.entity.CspReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * CSP 違反レポートリポジトリ。
 */
public interface CspReportRepository extends JpaRepository<CspReportEntity, Long> {

    /**
     * report_hash で CSP 違反レポートを検索する（重複集約用）。
     *
     * @param reportHash SHA-256 ハッシュ
     * @return 既存レポート（存在しない場合は empty）
     */
    Optional<CspReportEntity> findByReportHash(String reportHash);
}
