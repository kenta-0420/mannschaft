package com.mannschaft.app.moduleevent.notice;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M の読取 TX を終えた後にだけ呼ぶ、業務 resolver 評価と返却値検証の内部窓口。
 *
 * <p>caller TX が残る呼出しは拒否する。未接続を正常な空ページと区別し、不正な provider の
 * 結果を許可へ流さない。M Repository、通知内部型、payload deserialize は持たない。
 * 実 Spring による caller TX 停止と M 読取 TX の終了は、外側の段取りが別途担保する。</p>
 */
public final class ModuleEventImpactEvaluator {
    /** 既存 fanout worker の 1 チャンク 500 件を越えない。設定項目は追加しない。 */
    static final int MAX_RECIPIENTS = 500;

    private final ModuleEventImpactResolverRegistry registry;

    public ModuleEventImpactEvaluator(ModuleEventImpactResolverRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("resolver registry は必須");
        }
        this.registry = registry;
    }

    /** 未接続は UNKNOWN。M の pending=0 判定や実業務の完了判定を代行しない。 */
    public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) {
        requireNoTransaction();
        validateInputBinding(binding);
        Optional<ModuleEventImpactResolver> provider = registry.find(binding.getResolverKey());
        if (provider.isEmpty()) {
            return ModuleEventRecoveryState.UNKNOWN;
        }
        ModuleEventRecoveryState result = provider.get().recoveryState(binding);
        if (result == null) {
            throw new IllegalStateException("resolver が null の復旧状態を返した");
        }
        return result;
    }

    /** 未接続は Optional.empty、正常終端は Optional.of(emptyList) とする。 */
    public Optional<List<ModuleEventNoticeRecipient>> nextRecipients(
            ModuleEventNoticeDescriptor notice, long afterUserId, int limit) {
        requireNoTransaction();
        validateNotice(notice);
        if (afterUserId < 0 || limit < 1 || limit > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("受信者 cursor または上限が不正");
        }
        Optional<ModuleEventImpactResolver> provider = registry.find(notice.getImpact().getResolverKey());
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        List<ModuleEventNoticeRecipient> result = checkedRecipients(
                provider.get().nextRecipients(notice, afterUserId, limit), afterUserId, limit);
        return Optional.of(result);
    }

    /** sink の有界候補と dispatch singleton の現在許可を検証する。 */
    public Optional<List<ModuleEventNoticeRecipient>> eligibleRecipients(
            ModuleEventNoticeDescriptor notice, List<Long> candidateUserIds) {
        requireNoTransaction();
        validateNotice(notice);
        List<Long> candidates = checkedCandidates(candidateUserIds);
        Optional<ModuleEventImpactResolver> provider = registry.find(notice.getImpact().getResolverKey());
        if (provider.isEmpty()) {
            return Optional.empty();
        }
        List<ModuleEventNoticeRecipient> result = checkedRecipients(
                provider.get().eligibleRecipients(notice, candidates), 0, candidates.size());
        Set<Long> candidateSet = new HashSet<>(candidates);
        for (ModuleEventNoticeRecipient recipient : result) {
            if (!candidateSet.contains(recipient.getUserId())) {
                throw new IllegalStateException("resolver が候補外の受信者を返した");
            }
        }
        return Optional.of(result);
    }

    private void validateNotice(ModuleEventNoticeDescriptor notice) {
        if (notice == null || notice.getNoticeKey() == null
                || notice.getNoticeRef() == null || notice.getNoticeRef() <= 0
                || notice.getPhase() == null || notice.getAudience() == null) {
            throw new IllegalArgumentException("通知 descriptor が不正");
        }
        validateInputBinding(notice.getImpact());
    }

    private void validateInputBinding(ModuleEventImpactBinding binding) {
        if (binding == null) {
            throw new IllegalArgumentException("業務影響 binding は必須");
        }
        registry.validateBinding(binding, binding.getResolverKey());
    }

    private static List<Long> checkedCandidates(List<Long> candidates) {
        if (candidates == null || candidates.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("受信者候補の上限が不正");
        }
        List<Long> snapshot = new ArrayList<>(candidates);
        if (snapshot.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("受信者候補の上限が不正");
        }
        Set<Long> seen = new HashSet<>();
        for (Long id : snapshot) {
            if (id == null || id <= 0 || !seen.add(id)) {
                throw new IllegalArgumentException("受信者候補の ID が不正または重複");
            }
        }
        return List.copyOf(snapshot);
    }

    private static List<ModuleEventNoticeRecipient> checkedRecipients(
            List<ModuleEventNoticeRecipient> recipients, long afterUserId, int limit) {
        if (recipients == null || recipients.size() > limit) {
            throw new IllegalStateException("resolver が null または上限超過の受信者を返した");
        }
        List<ModuleEventNoticeRecipient> snapshot = new ArrayList<>(recipients);
        if (snapshot.size() > limit) {
            throw new IllegalStateException("resolver が上限超過の受信者を返した");
        }
        long previousId = afterUserId;
        for (ModuleEventNoticeRecipient recipient : snapshot) {
            if (recipient == null || recipient.getUserId() == null || recipient.getUserId() <= previousId) {
                throw new IllegalStateException("resolver の受信者 ID が不正・重複・未整列");
            }
            previousId = recipient.getUserId();
        }
        return List.copyOf(snapshot);
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("業務 resolver の現在評価へ caller TX を持ち込めない");
        }
    }
}
