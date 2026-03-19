package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 監査ログリポジトリ。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
}
