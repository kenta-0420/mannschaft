package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.InviteTokenRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 招待の確保記録（{@code invite_token_claims}）の取り残し監視（CMP-260826-1457 §9.6・回復 X2）。
 *
 * <p>承諾の途中で停止・障害が起きると確保が CLAIMED のまま残り、その分だけ使用回数が減ったままになる
 * （受入れが減る安全側の失敗）。照合バッチは出さず、取り残しを gauge で可視化する。gauge は scrape 毎に
 * DB を照会する（先例: {@code EmailOutboxMicrometerMetrics}）。</p>
 *
 * <ul>
 *   <li>{@value #STALE_COUNT}: 作成から {@value #STALE_THRESHOLD_SECONDS} 秒（10分）を超えて CLAIMED の件数</li>
 *   <li>{@value #OLDEST_AGE}: 最古の CLAIMED の滞留秒（無ければ 0）</li>
 * </ul>
 *
 * <p>警報の目安: {@code stale_claimed_count > 0} が持続（通常は承諾1回が数百ミリ秒で終わるため、
 * 10分超の CLAIMED は異常）。{@code oldest_claimed_age_seconds} が増え続ける場合は調査し、
 * 付与の有無（user_roles / memberships）を確かめたうえで手動で処理する。自動の release は、付与と確保の確定を
 * 同一トランザクションにして証拠を残せるようにするまで入れない（陣立て書 §9.7）。</p>
 */
@Component
@RequiredArgsConstructor
public class InviteTokenClaimMetrics {

    static final String STALE_COUNT = "invite_token_claims.stale_claimed_count";
    static final String OLDEST_AGE = "invite_token_claims.oldest_claimed_age_seconds";
    static final long STALE_THRESHOLD_SECONDS = 600;

    private final MeterRegistry meterRegistry;
    private final InviteTokenRepository inviteTokenRepository;

    @PostConstruct
    void registerGauges() {
        Gauge.builder(STALE_COUNT, inviteTokenRepository, r -> r.countStaleClaimed(STALE_THRESHOLD_SECONDS))
                .description("作成から10分を超えて CLAIMED のままの招待確保記録の件数")
                .register(meterRegistry);
        Gauge.builder(OLDEST_AGE, inviteTokenRepository, InviteTokenRepository::oldestClaimedAgeSeconds)
                .description("最古の CLAIMED の招待確保記録の滞留秒。CLAIMED が無ければ 0")
                .register(meterRegistry);
    }
}
