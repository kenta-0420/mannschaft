package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 広告レポートスケジュールリポジトリ。
 */
public interface AdReportScheduleRepository extends JpaRepository<AdReportScheduleEntity, Long> {

    /**
     * 広告主アカウントIDでスケジュールを取得する。
     */
    List<AdReportScheduleEntity> findByAdvertiserAccountId(Long accountId);

    /**
     * 広告主アカウントIDでスケジュール数をカウントする。
     */
    long countByAdvertiserAccountId(Long accountId);

    /**
     * 有効かつ指定頻度のスケジュールを取得する。
     */
    List<AdReportScheduleEntity> findByEnabledTrueAndFrequency(ReportFrequency frequency);
}
