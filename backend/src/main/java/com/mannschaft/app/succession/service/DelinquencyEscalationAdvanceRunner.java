package com.mannschaft.app.succession.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;

/**
 * 滞納エスカレーション日次バッチ用の 1 件昇格 REQUIRES_NEW 実行 Bean（Issue #2601）。
 *
 * <p>{@link DelinquencyEscalationBatchService#advanceEscalations()} からループで呼ばれる。
 * バッチ失敗時のリトライ安全性を確保するため、1 件の昇格 = 1 独立トランザクションとする必要があり、
 * 独立した Bean に切り出し {@link Propagation#REQUIRES_NEW} を付与する
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。
 *
 * <p>{@link DelinquencyEscalationService#advanceStage} 自体の伝播設定（既定の {@code REQUIRED}）は
 * 変更しない。同メソッドは本 Bean（日次バッチ経路）以外からも呼ばれる可能性を考慮し、
 * 既存の呼び出し元（{@link DelinquencyEscalationListener} 等）に影響を与えないため。
 */
@Component
@RequiredArgsConstructor
class DelinquencyEscalationAdvanceRunner {

    private final DelinquencyEscalationService escalationService;

    /**
     * 指定エスカレーションを次のステージに独立トランザクションで進める。
     *
     * @param escalationId   エスカレーション ID
     * @param organizationId 組織 ID（テナント分離）
     * @return 更新後のエンティティ
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DelinquencyEscalationEntity advanceStage(UUID escalationId, Long organizationId) {
        return escalationService.advanceStage(escalationId, organizationId);
    }
}
