package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * grace window 超過の後継有りトークン再提示を、同一端末（deviceFingerprint 一致）からの再試行と
 * 判定して救済（全デバイス無効化を回避）したことを表す監査イベント。
 *
 * <p>CMP-260917-1352 Phase 3。{@link TokenReuseDetectedEvent}（真リプレイ検出・全デバイス無効化）とは
 * 意図的に型を分けている。同一イベント型に混ぜると監視側で「本物の盗難検知」と区別が付かなくなり、
 * 誤報が増えて本物のアラートへの感度が下がる。</p>
 *
 * <p>本イベントが発行される救済経路自体、User-Agent ハッシュというなりすまし可能な弱い identity
 * による判定である（{@code docs/security/06_business_logic_and_abuse_prevention.md} §7.9.4 参照）。
 * 全デバイス無効化はしないが、「grace 超過の再提示が起き、同一端末と判定して救済した」という事実は
 * 必ず監査ログへ記録し、事後の異常検知・調査を可能にする。</p>
 */
@Getter
public class TokenReplaySameDeviceRescuedEvent extends BaseEvent {

    private final Long userId;
    /** grace 超過で再提示された（起点となった）トークンの DB ID。 */
    private final Long staleTokenId;
    /** 救済により基点として新トークンを発行した、後継チェーンの現行トークンの DB ID。 */
    private final Long rescuedFromTokenId;

    public TokenReplaySameDeviceRescuedEvent(Long userId, Long staleTokenId, Long rescuedFromTokenId) {
        super();
        this.userId = userId;
        this.staleTokenId = staleTokenId;
        this.rescuedFromTokenId = rescuedFromTokenId;
    }
}
