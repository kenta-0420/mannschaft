package com.mannschaft.app.common.storage.quota.repository;

import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F13 ストレージ使用量変動ログのリポジトリ（INSERT のみ）。
 */
public interface StorageUsageLogRepository extends JpaRepository<StorageUsageLogEntity, Long> {
}
