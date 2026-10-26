package com.mannschaft.app.auth.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.member.event.MemberSubtabVisibilityUpdatedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-14b（軍議書 gungi-3387-d3t.md 第3版 §2.1(d)・§7）。
 *
 * <p>{@link AuditLogEventListener} の新設ハンドラは、受け取った {@link MemberSubtabVisibilityUpdatedEvent} の
 * 値を加工せずに <b>同期版</b> {@link AuditLogService#recordSync} へ渡す。非同期版 {@code record} を呼ぶと
 * {@code @Async("event-pool")} のリスナーから同じ event-pool へ二重に積むことになる（第3版で是正）。</p>
 *
 * <p>組み立て（出陣で合わせること）: イベントは {@code new MemberSubtabVisibilityUpdatedEvent(actorUserId,
 * teamId, organizationId, metadataJson)}、ハンドラ名は {@code handleMemberSubtabVisibilityUpdated}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PR #3387 D-3T 試練 AC-14b: 監査リスナーはイベントの値をそのまま recordSync へ渡す")
class AuditLogEventListenerMemberSubtabTest {

    private static final String EVENT_TYPE = "MEMBER_SUBTAB_VISIBILITY_UPDATED";

    @Mock private AuditLogService auditLogService;

    private AuditLogEventListener listener() {
        return new AuditLogEventListener(auditLogService, new ObjectMapper());
    }

    @Test
    @DisplayName("組織スコープ: 9引数が完全一致で recordSync へ渡る・record は呼ばない")
    void 組織スコープの値はそのまま渡る() {
        String metadata = "{\"scope_type\":\"ORGANIZATION\",\"scope_id\":200,"
                + "\"changes\":[{\"subtab_key\":\"member_profiles\",\"before\":\"MEMBER\",\"after\":\"PUBLIC\"}]}";

        listener().handleMemberSubtabVisibilityUpdated(
                new MemberSubtabVisibilityUpdatedEvent(1L, null, 200L, metadata));

        verify(auditLogService).recordSync(EVENT_TYPE, 1L, null, null, 200L, null, null, null, metadata);
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(auditLogService);
    }

    @Test
    @DisplayName("チームスコープ: teamId がそのまま渡る")
    void チームスコープの値はそのまま渡る() {
        String metadata = "{\"scope_type\":\"TEAM\",\"scope_id\":300,\"changes\":[]}";

        listener().handleMemberSubtabVisibilityUpdated(
                new MemberSubtabVisibilityUpdatedEvent(7L, 300L, null, metadata));

        verify(auditLogService).recordSync(EVENT_TYPE, 7L, null, 300L, null, null, null, null, metadata);
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("metadata を作り直さない: 任意の文字列（JSON でなくても）が同じ文字列のまま渡る")
    void metadataを加工しない() {
        String arbitrary = "not-json <そのまま> {\"b\":1,\"a\":2}";

        listener().handleMemberSubtabVisibilityUpdated(
                new MemberSubtabVisibilityUpdatedEvent(1L, null, 200L, arbitrary));

        verify(auditLogService).recordSync(EVENT_TYPE, 1L, null, null, 200L, null, null, null, arbitrary);
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("注釈3点: BackgroundFeaturePolicy(ALWAYS)・@Async(\"event-pool\")・AFTER_COMMIT")
    void 注釈はcirculationと同じ3点() throws Exception {
        Method handler = AuditLogEventListener.class.getMethod(
                "handleMemberSubtabVisibilityUpdated", MemberSubtabVisibilityUpdatedEvent.class);

        BackgroundFeaturePolicy policy = handler.getAnnotation(BackgroundFeaturePolicy.class);
        assertThat(policy).isNotNull();
        assertThat(policy.mode()).isEqualTo(BackgroundFeatureMode.ALWAYS);

        Async async = handler.getAnnotation(Async.class);
        assertThat(async).isNotNull();
        assertThat(async.value()).isEqualTo("event-pool");

        TransactionalEventListener tel = handler.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
