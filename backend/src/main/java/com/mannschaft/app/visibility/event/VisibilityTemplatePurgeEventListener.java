package com.mannschaft.app.visibility.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.visibility.entity.VisibilityTemplateEntity;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRepository;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRuleRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 退会者が作ったカスタム公開範囲テンプレート（F01.7）を30日後に強消去する。
 *
 * <p>ユーザー行は物理削除されず FK の ON DELETE CASCADE は発火しない。また
 * {@code visibility_template_id} への SET NULL FK は V110.001 で撤廃済みなので、ここで明示的に消す。
 * 消えたテンプレートを参照する投稿は {@code VisibilityTemplateEvaluator#canView} が fail-closed（false）で
 * 扱うため、本人以外には見えない（PRIVATE 相当）。システムプリセット（owner NULL）と他人のテンプレートは対象外。</p>
 */
@Component
@RequiredArgsConstructor
public class VisibilityTemplatePurgeEventListener {

    private final VisibilityTemplateRepository visibilityTemplateRepository;
    private final VisibilityTemplateRuleRepository visibilityTemplateRuleRepository;
    private final VisibilityTemplateEvaluator visibilityTemplateEvaluator;
    private final AccountPurgeCompletionService completionService;

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の公開範囲テンプレートを消去する。停止すると残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purgeTemplates(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "visibility");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeTemplates(userId);
        return true;
    }

    /** 子ルール→テンプレートの順に同じTXで消し、途中失敗を伝播させる。 */
    private void purgeTemplates(Long userId) {
        List<Long> templateIds = visibilityTemplateRepository.findByOwnerUserIdOrderByCreatedAtDesc(userId)
                .stream().map(VisibilityTemplateEntity::getId).toList();
        visibilityTemplateRuleRepository.deleteAllByOwnerUserId(userId);
        visibilityTemplateRepository.deleteAllByOwnerUserId(userId);
        templateIds.forEach(visibilityTemplateEvaluator::evictTemplateCache);
    }
}
