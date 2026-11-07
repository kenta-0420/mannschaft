package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.List;

/** 源ごとの短い独立TX窓口。COREは本文/源Repoを持たず、この公開値だけを順次渡す。 */
public interface SourceOutboxDeliveryFacade {
    RanchRewardSourceType sourceType();
    List<SourceOutboxLeasedEvent> lease(SourceOutboxLeaseRequest request);
    /** 現token/LEASED/未期限切れに一致した時だけACK。削除/旧tokenはfalseで再作成しない。 */
    boolean acknowledge(SourceOutboxAckRequest request);
    /** 実障害に指数backoff+jitterと失敗上限を適用する。 */
    boolean retry(SourceOutboxFailureRequest request);
    /** 当leaseの増算分を一回だけ戻し、次eligible時刻まで非終端保留する。 */
    boolean defer(SourceOutboxDeferRequest request);
}
