package com.mannschaft.app.webhook.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Webhook配信ログクエリリポジトリ（JdbcTemplate使用）。
 * バッチ処理・大量削除など JPA では表現しにくい操作を担当する。
 */
@Repository
@RequiredArgsConstructor
public class WebhookDeliveryLogQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 指定日時より古い配信ログをバッチ削除する（30日パージ用）。
     *
     * @param threshold 削除対象の閾値日時（この日時より前に作成されたレコードを削除）
     * @param batchSize 一度に削除する最大件数
     * @return 削除件数
     */
    public int deleteOlderThan(LocalDateTime threshold, int batchSize) {
        String sql = """
                DELETE FROM webhook_delivery_logs
                WHERE created_at < ?
                LIMIT ?
                """;
        return jdbcTemplate.update(sql, threshold, batchSize);
    }
}
