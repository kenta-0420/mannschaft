package com.mannschaft.app.circulation.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 押印済み証跡 PDF エクスポートが生成完了したイベント（F05.2 Phase 11 4-C）。
 *
 * <p>{@link com.mannschaft.app.circulation.service.CirculationExportAsyncExecutor#generateAsync} が発行し、
 * {@link com.mannschaft.app.auth.event.AuditLogEventListener} が購読して監査ログを記録する。
 *
 * <p>循環ドメインから auth ドメインの {@code AuditLogService} を {@code @Transactional} 内で
 * 直接呼び出す設計はドメイン境界原則5に違反するため、イベント駆動で分離した。</p>
 */
@Getter
public class CirculationExportGeneratedEvent extends BaseEvent {

    private final Long actorId;
    private final Long documentId;
    private final Long teamId;
    private final Long organizationId;

    public CirculationExportGeneratedEvent(Long actorId, Long documentId,
                                           Long teamId, Long organizationId) {
        super();
        this.actorId = actorId;
        this.documentId = documentId;
        this.teamId = teamId;
        this.organizationId = organizationId;
    }
}
