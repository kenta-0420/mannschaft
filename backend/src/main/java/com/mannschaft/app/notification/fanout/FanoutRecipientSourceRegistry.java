package com.mannschaft.app.notification.fanout;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link FanoutRecipientSource} を {@code scope_type} で引くレジストリ（P2・AC-15 の seam）。
 *
 * <p>Spring が全 {@link FanoutRecipientSource} 実装を {@code List} 注入し、{@link #scopeType()} をキーに
 * {@code Map} を組む。ワーカーはジョブの {@code scope_type} から本レジストリで実装を解決するため、
 * 新スコープの追加が「実装 1 つ追加」で完結する。</p>
 *
 * <p>本クラスは構造（ルーティング）であり fan-out ロジックを持たないため完全実装する
 * （red は enqueue／ワーカー本体側で表現する）。</p>
 */
@Component
public class FanoutRecipientSourceRegistry {

    private final Map<String, FanoutRecipientSource> byScopeType;

    public FanoutRecipientSourceRegistry(List<FanoutRecipientSource> sources) {
        Map<String, FanoutRecipientSource> map = new HashMap<>();
        for (FanoutRecipientSource source : sources) {
            FanoutRecipientSource previous = map.put(source.scopeType(), source);
            if (previous != null) {
                throw new IllegalStateException(
                        "FanoutRecipientSource の scope_type が重複: " + source.scopeType()
                                + "（" + previous.getClass().getName() + " と " + source.getClass().getName() + "）");
            }
        }
        this.byScopeType = Map.copyOf(map);
    }

    /** {@code scopeType} に対応する受信者ソースを返す（未登録なら空）。 */
    public Optional<FanoutRecipientSource> resolve(String scopeType) {
        return Optional.ofNullable(byScopeType.get(scopeType));
    }
}
