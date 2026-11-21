package com.mannschaft.app.match;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 成功した記録開始だけを、既存の非同期監査へ渡す。
 * 業務TXにauthドメインを参加させず、失敗した開始の監査を発行しない。
 */
@Component
@RequiredArgsConstructor
public class MatchRecordingStartedAuditListener {
    private final AuditLogService auditLogService;

    /** コミット済みのimmutable値を既存Asyncプロキシへ渡す（監査はbest-effort）。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "記録開始の監査は対応するgate_keyが無いため常時実行する。業務コミット後の監査記録。")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecordingStarted(MatchRecordingStartedEvent event) {
        String metadata = String.format(
                "{\"matchId\":\"%s\",\"teamId\":%s,\"before\":\"%s\",\"after\":\"%s\"}",
                event.matchId(), event.teamId(), event.before(), event.after());
        auditLogService.record(AuditEventType.MATCH_STATUS_CHANGED.name(), event.actorUserId(), null,
                event.teamId(), event.organizationId(), null, null, null, metadata);
    }
}
