package com.mannschaft.app.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 監査ログをアーカイブDBへ移送するサービス。
 * app.archive.db.enabled=true のときのみ有効化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.archive.db.enabled", havingValue = "true")
public class AuditLogArchiveDbService {

    @Qualifier("archiveJdbcTemplate")
    private final JdbcTemplate archiveJdbcTemplate;

    private static final String INSERT_SQL = """
            INSERT IGNORE INTO audit_logs_archive
                (id, event_type, user_id, scope_type, scope_id, ip_address,
                 user_agent, detail, created_at, archived_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * audit_logs のレコードリストをアーカイブDBへ移送する。
     * 移送元は R2 アーカイブバッチが DROP PARTITION 前に呼び出す。
     *
     * @param rows 移送対象レコード（Map のリスト）
     */
    public void exportToArchiveDb(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        int exported = 0;
        for (Map<String, Object> row : rows) {
            try {
                archiveJdbcTemplate.update(INSERT_SQL,
                        row.get("id"),
                        row.get("event_type"),
                        row.get("user_id"),
                        row.get("scope_type"),
                        row.get("scope_id"),
                        row.get("ip_address"),
                        row.get("user_agent"),
                        row.get("detail"),
                        row.get("created_at"),
                        LocalDateTime.now()
                );
                exported++;
            } catch (Exception e) {
                log.error("アーカイブDB移送失敗: id={}", row.get("id"), e);
            }
        }
        log.info("アーカイブDB移送完了: {}件", exported);
    }
}
