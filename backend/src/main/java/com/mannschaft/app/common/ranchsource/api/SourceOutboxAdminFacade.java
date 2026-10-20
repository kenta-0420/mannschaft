package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.util.UUID;

/** 四源自身の管理公開契約。認可済み非TX窓口から、各源の独立TXを順次呼ぶ。 */
public interface SourceOutboxAdminFacade {
    /** 四源の集計だけを返す。本文・利用者ID・私有hash・lease tokenは返さない。 */
    SourceOutboxHealthSummary health();

    /**
     * fresh SYSTEM_ADMINかつACTIVEの本人窓口が渡すactorだけを監査へ使う。
     * sourceType/eventId/key/reasonの同key成功ACKを先に読み、別bodyは409。
     * 同eventの再予約だけを行い、occurredAt/canonical key/報酬を新規作成しない。
     */
    SourceOutboxAdminRetryAck retry(Long actorUserId, RanchRewardSourceType sourceType,
            UUID eventId, UUID idempotencyKey, SourceOutboxAdminRetryRequest request);
}
