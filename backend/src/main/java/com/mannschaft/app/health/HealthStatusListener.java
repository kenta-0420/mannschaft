package com.mannschaft.app.health;

import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.service.ErrorReportActivityService;
import com.mannschaft.app.errorreport.service.ErrorReportNotifier;
import com.mannschaft.app.errorreport.service.ErrorReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F10.5 Phase 10-β-2 / F10.6 §5.4 — {@code /actuator/health} の component 状態を 30 秒毎にポーリングし、
 * UP → DOWN 遷移を検知したら {@link ErrorReportNotifier#notifyHealthDown(String, String)} を呼ぶリスナー。
 *
 * <p>主要 component（{@code db} / {@code redis} 等）の状態遷移を監視し、
 * インフラ層の障害を Slack に即時通知する。同一 component の DOWN が連続発生する場合は
 * {@link ErrorReportNotifier} 側の 5 分クールダウンキャッシュで重複アラートが抑制される。</p>
 *
 * <p><b>設計上の判断:</b></p>
 * <ul>
 *   <li>個別 {@code HealthIndicator} を直接注入するのではなく、Spring Boot 3.x の
 *       {@link HealthEndpoint} を使い、Actuator 自身が組み立てた集約状態を参照する。
 *       これにより MySQL / Redis 以外の component（カスタム HealthIndicator 含む）も
 *       自動的に監視対象に入る。</li>
 *   <li>初回ポーリングは状態を記録するだけで通知しない（起動直後にすべての component が
 *       「UP に遷移した」と誤検知してしまうのを防ぐ）。</li>
 *   <li>DOWN → UP 復旧通知は送らない（ノイズ削減）。代わりに {@code error_report_activities} に
 *       {@link ErrorReportActivityType#HEALTH_RECOVERED} を記録する（Phase 10-γ-① 実装済み）。</li>
 *   <li>DOWN 検知時に {@link ErrorReportService#findOrCreateHealthDownReport(String)} を呼んで
 *       {@code error_reports} にレコードを作成／集約し、その ID を {@code componentToReportId} に保存する。
 *       復旧時はこの ID を使って {@code HEALTH_RECOVERED} アクティビティを記録する。</li>
 *   <li>self-invocation 罠を避けるため、{@code @Async} の付いた
 *       {@link ErrorReportNotifier#notifyHealthDown} は別 Bean (Notifier) 経由で呼ぶ。
 *       本クラスは {@code @Scheduled} のみで、self-invocation はしない。</li>
 * </ul>
 *
 * @see ErrorReportNotifier#notifyHealthDown(String, String)
 */
@Component
@Slf4j
public class HealthStatusListener {

    private final HealthEndpoint healthEndpoint;
    private final ErrorReportNotifier errorReportNotifier;
    private final ErrorReportService errorReportService;
    private final ErrorReportActivityService errorReportActivityService;
    private final boolean enabled;

    /** 前回ポーリング時の component 別 status（component 名 → Status）。 */
    private final Map<String, Status> previousStatus = new ConcurrentHashMap<>();

    /**
     * DOWN 検知時に作成した error_report_id のキャッシュ（component 名 → error_report_id）。
     * 復旧（DOWN→UP）時の {@link ErrorReportActivityType#HEALTH_RECOVERED} 記録に使用する。
     */
    private final Map<String, Long> componentToReportId = new ConcurrentHashMap<>();

    /** 初回ポーリング判定。{@code true} の間は遷移検知を行わず、状態の記録のみを行う。 */
    private volatile boolean firstPoll = true;

    public HealthStatusListener(HealthEndpoint healthEndpoint,
                                 ErrorReportNotifier errorReportNotifier,
                                 ErrorReportService errorReportService,
                                 ErrorReportActivityService errorReportActivityService,
                                 @Value("${mannschaft.error-monitoring.health-polling.enabled:true}") boolean enabled) {
        this.healthEndpoint = healthEndpoint;
        this.errorReportNotifier = errorReportNotifier;
        this.errorReportService = errorReportService;
        this.errorReportActivityService = errorReportActivityService;
        this.enabled = enabled;
    }

    /**
     * 30 秒間隔で /actuator/health の component 状態をポーリングする。
     *
     * <p>UP → DOWN 遷移を検知した component について {@link ErrorReportNotifier#notifyHealthDown}
     * を呼び出す。DOWN → UP 復旧は通知しないが、{@code error_report_activities} に
     * {@link ErrorReportActivityType#HEALTH_RECOVERED} を記録する（Phase 10-γ-① 実装済み）。</p>
     *
     * <p>{@code fixedDelay} を採用しているため、前回呼び出しが遅延しても呼び出しが重ならない。
     * Health の取得自体が遅い場合（DB 接続タイムアウト等）でも safe。</p>
     */
    @Scheduled(fixedDelayString = "${mannschaft.error-monitoring.health-polling.interval-ms:30000}",
               initialDelayString = "${mannschaft.error-monitoring.health-polling.interval-ms:30000}")
    public void pollHealthStatus() {
        if (!enabled) return;
        try {
            doPoll();
        } catch (Exception e) {
            // ポーリング自体が落ちると後続が止まるため、必ず握って構造化ログだけ残す。
            log.warn("Health ポーリング失敗", e);
        }
    }

    /**
     * 実ポーリング処理。テスト容易性のため public で切り出している。
     */
    public void doPoll() {
        HealthComponent root = healthEndpoint.health();
        Map<String, HealthComponent> components = extractComponents(root);
        if (components.isEmpty()) {
            // root 自体しか存在しない場合は root 単独の status を観測対象にする
            updateAndDetect("application", root.getStatus(), "(no detail)");
            return;
        }
        for (Map.Entry<String, HealthComponent> entry : components.entrySet()) {
            String component = entry.getKey();
            HealthComponent hc = entry.getValue();
            String detail = renderDetail(hc);
            updateAndDetect(component, hc.getStatus(), detail);
        }
        // 1 周回り終わったら以降は遷移検知モードへ
        firstPoll = false;
    }

    /**
     * component の前回 status を更新し、UP → DOWN 遷移を検知したら通知する。
     */
    private void updateAndDetect(String component, Status currentStatus, String detail) {
        Status prev = previousStatus.put(component, currentStatus);
        if (firstPoll) {
            // 初回は記録のみ
            return;
        }
        if (prev == null) {
            // 途中から observed されるようになった component（カスタム HealthIndicator が後から登場した等）。
            // 突然 DOWN で初観測したら通知する（誤検知を避けるため UP 初観測時は無通知）。
            if (Status.DOWN.equals(currentStatus) || Status.OUT_OF_SERVICE.equals(currentStatus)) {
                log.warn("Health 初観測 DOWN: component={}, status={}", component, currentStatus);
                errorReportNotifier.notifyHealthDown(component, detail);
            }
            return;
        }
        boolean wasUp = Status.UP.equals(prev) || Status.UNKNOWN.equals(prev);
        boolean isDown = Status.DOWN.equals(currentStatus) || Status.OUT_OF_SERVICE.equals(currentStatus);
        if (wasUp && isDown) {
            log.warn("Health 状態遷移 UP→DOWN 検知: component={}, status={}", component, currentStatus);
            errorReportNotifier.notifyHealthDown(component, detail);
            // error_reports にレコードを作成し、復旧時の activity 記録用に ID を保持する
            try {
                Long reportId = errorReportService.findOrCreateHealthDownReport(component);
                if (reportId != null) {
                    componentToReportId.put(component, reportId);
                }
            } catch (Exception e) {
                log.warn("Health DOWN error_reports 記録失敗: component={}", component, e);
            }
        } else if (Status.DOWN.equals(prev) && Status.UP.equals(currentStatus)) {
            // F10.6 Phase 10-γ-① — 復旧を error_report_activities に HEALTH_RECOVERED として記録する
            log.info("Health 状態遷移 DOWN→UP 復旧: component={}", component);
            Long reportId = componentToReportId.get(component);
            if (reportId != null) {
                try {
                    Map<String, Object> metadata = Map.of(
                            "component", component,
                            "restoredAt", LocalDateTime.now().toString()
                    );
                    errorReportActivityService.recordSystemActivity(reportId, ErrorReportActivityType.HEALTH_RECOVERED, metadata);
                    log.info("Health 復旧アクティビティ記録完了: component={}, reportId={}", component, reportId);
                } catch (Exception e) {
                    log.warn("Health 復旧 activity 記録失敗: component={}, reportId={}", component, reportId, e);
                }
            } else {
                log.warn("Health 復旧時の error_report_id が見つからない（初回復旧または再起動後）: component={}", component);
            }
        }
    }

    /**
     * ルート {@link HealthComponent} から子 component の Map を抽出する。
     *
     * <p>{@link CompositeHealth} の場合は {@code getComponents()} を使い、それ以外は空 Map を返す。</p>
     */
    private Map<String, HealthComponent> extractComponents(HealthComponent root) {
        if (root instanceof CompositeHealth composite) {
            Map<String, HealthComponent> sub = composite.getComponents();
            return sub != null ? sub : Map.of();
        }
        return Map.of();
    }

    /**
     * {@link HealthComponent} から人間可読の detail 文字列を組み立てる。
     *
     * <p>{@code Health} には details Map が付随することがあるが、PII / 機密情報を含む可能性があるため
     * 通知本文には status のみを載せ、詳細はサーバログで参照させる方針とする。</p>
     */
    private String renderDetail(HealthComponent hc) {
        return "status=" + hc.getStatus().getCode();
    }
}
