package com.mannschaft.app.team.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link TeamAffiliationAuditRecorder} の実装。{@link AuditLogService#recordSync} を呼び出しスレッド・
 * 呼び出し元のトランザクションで実行する（F01.2.1 §4.1・§6.1 step 13）。
 *
 * <p>非同期の {@code record} ではなく同期の {@code recordSync} を使うのは、「操作は成功したのに監査行が
 * まだ無い」時間窓を作らないため。監査行は操作と同じトランザクションに参加するので、操作がロールバック
 * されれば監査行も残らない。他ドメインから監査を書く経路は {@link AuditLogService} に限る
 * （{@code AuditLogRepository} を直接触ると境界の番人が拒否する）。</p>
 */
@Component
@RequiredArgsConstructor
public class AuditLogTeamAffiliationAuditRecorder implements TeamAffiliationAuditRecorder {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @Override
    public void record(AuditEventType eventType, Long actorUserId, Long teamId, Long organizationId,
                       Map<String, Object> metadata) {
        auditLogService.recordSync(eventType.name(), actorUserId, null, teamId, organizationId,
                null, null, null, toJson(metadata));
    }

    private String toJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            // metadata は ID と列挙値だけで組むため通常は失敗しない。握り潰さず、監査行の欠落として原因を残す
            throw new IllegalStateException("加盟の監査ログ metadata を JSON にできない", e);
        }
    }
}
