package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/** Ranch commit後の別threadで既存監査serviceへ渡し、Ranch writerのTXでauth repoを呼ばない。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RanchAdminAuditListener {
    private final AuditLogService audit;
    private final ObjectMapper json;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "管理公開と停止操作の監査事実は再生されず、止めると証跡が恒久的に欠落する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void applied(RanchAdminActionRecorded event) {
        try {
            String metadata = json.writeValueAsString(Map.of("commandId", event.commandId().toString(),
                    "action", event.action(), "resourceId", event.resourceId(),
                    "reasonCode", event.reasonCode(), "occurredAt", event.occurredAt().toString()));
            audit.record(AuditEventType.RANCH_ADMIN_ACTION_APPLIED.name(), event.actorUserId(),
                    null, null, null, null, null, null, metadata);
        } catch (JsonProcessingException exception) {
            // 管理者の入力や設定全体をexception経由でログへ出さない。
            log.error("牧場管理監査metadataの符号化失敗: commandId={}", event.commandId());
        }
    }
}