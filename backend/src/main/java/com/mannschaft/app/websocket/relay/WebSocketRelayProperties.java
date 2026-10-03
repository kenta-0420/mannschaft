package com.mannschaft.app.websocket.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 中継の feature flag（設計書 §1.3 / §4.1）。
 *
 * <p>プロパティは {@code enabled} のみ（既定 {@code false}）。チャネル名は
 * {@link WebSocketRelayConstants} の定数、nodeId は起動時生成 UUID のためプロパティ化しない。</p>
 *
 * <p>relay 部品は {@code @ConditionalOnProperty(prefix="mannschaft.websocket.relay",
 * name="enabled", havingValue="true")} で <b>flag OFF 時は Bean 不生成</b>とする（§1.3・AC-3）。
 * 本 flag の解釈と Bean 条件付き生成は出陣隊が {@link WebSocketRelayConfig} に実装する。</p>
 */
@ConfigurationProperties(prefix = "mannschaft.websocket.relay")
public class WebSocketRelayProperties {

    /** 中継の有効化フラグ（既定 false = 現行 SimpleBroker と完全同一挙動・§1.3）。 */
    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
