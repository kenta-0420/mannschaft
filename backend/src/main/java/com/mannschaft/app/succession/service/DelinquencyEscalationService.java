package com.mannschaft.app.succession.service;

import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;

import java.util.List;
import java.util.UUID;

/**
 * 滞納エスカレーションサービス インターフェース（F09.15 S5-A / S5-B）。
 *
 * <p>本インターフェースは S5-A（DelinquencyEscalationService 実装）が
 * マージされるまでのコンパイル通過用スタブとして配置する。
 * S5-A マージ後に実装クラスと統合すること。
 *
 * <p>TODO: S5-A マージ後にこのスタブ宣言を削除し、実装クラスに置き換えること。
 */
public interface DelinquencyEscalationService {

    /**
     * 組織内の未解決エスカレーション一覧を取得する。
     *
     * @param organizationId テナント組織 ID
     * @return 未解決エスカレーションのリスト
     */
    List<DelinquencyEscalationEntity> listActive(Long organizationId);

    /**
     * エスカレーション ID と組織 ID でエスカレーションを取得する。
     *
     * @param escalationId   エスカレーション ID（UUID）
     * @param organizationId テナント組織 ID
     * @return エスカレーションエンティティ
     */
    DelinquencyEscalationEntity getById(UUID escalationId, Long organizationId);

    /**
     * エスカレーションを凍結する（弁護士介入等）。
     *
     * @param escalationId   エスカレーション ID（UUID）
     * @param organizationId テナント組織 ID
     * @param reason         凍結理由
     */
    void freeze(UUID escalationId, Long organizationId, String reason);

    /**
     * エスカレーションを解決済みに遷移させる。
     *
     * @param escalationId   エスカレーション ID（UUID）
     * @param organizationId テナント組織 ID
     * @param resolvedReason 解決理由コード（PAID / DEATH_CONFIRMED / MANUAL_CLOSE 等）
     */
    void resolve(UUID escalationId, Long organizationId, String resolvedReason);
}
