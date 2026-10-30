package com.mannschaft.app.errorreport.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.errorreport.ErrorReportSeverity;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import com.mannschaft.app.moduleevent.api.event.ModuleEventOperationalAlertEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * M 公開イベントを errorreport 自領域の既存記録経路へ渡す adapter。
 * 非同期処理は既存 ErrorReportAsyncExecutor に任せ、ここでは二重に @Async を付けない。
 * 実 DB 保存・AFTER_COMMIT 通知の到達は後続 IT の必須 AC とする。
 */
@Component
public class ModuleEventOperationalAlertListener {
    private final ErrorReportService errorReportService;

    public ModuleEventOperationalAlertListener(ErrorReportService errorReportService) {
        this.errorReportService = Objects.requireNonNull(errorReportService, "errorReportService");
    }

    /** 更新確定後に TX 外から届く公開イベントを購読する。 */
    @EventListener
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "M 基盤の隔離と継続遅延の運用警報は機能公開状態から独立し、停止すると障害検知と復旧対応が欠落する")
    public void onOperationalAlert(ModuleEventOperationalAlertEvent event) {
        IllegalStateException failure = switch (event.kind()) {
            case QUARANTINED -> quarantinedFailure();
            case OLDEST_PENDING_30_MIN -> oldestPendingFailure();
        };
        errorReportService.recordBackendException(failure, null, ErrorReportSeverity.HIGH);
    }

    // 既存 errorreport の集約は例外クラスと先頭 frame を使うため、種別ごとに生成箇所を分ける。
    private static IllegalStateException quarantinedFailure() {
        return new IllegalStateException("MODULE_EVENT_QUARANTINED");
    }

    private static IllegalStateException oldestPendingFailure() {
        return new IllegalStateException("MODULE_EVENT_OLDEST_PENDING_30_MIN");
    }
}
