package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 監査ログリポジトリ。
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
}
