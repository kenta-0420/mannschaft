package com.mannschaft.app.moduleevent.api;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeClaim;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticePhase;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactEvaluator;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeReadService;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeRelayService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import java.util.HashSet;
import java.util.function.Function;
import java.util.Objects;
import java.util.Optional;

/**
 * notification から一方向に呼ぶ公開窓口。HTTP・Entity・通知内部型を公開しない。
 * caller を NOT_SUPPORTED で停止し、M の独立読取終了後だけ業務 SPI を呼ぶ段取りを担う。
 */
@Component
public final class ModuleEventNoticeOutboxApi {
    private final ModuleEventNoticeReadService reads;
    private final ModuleEventNoticeRelayService relay;
    private final ModuleEventImpactEvaluator evaluator;
    private final TransactionTemplate withoutCallerTransaction;

    public ModuleEventNoticeOutboxApi(ModuleEventNoticeReadService reads, ModuleEventImpactEvaluator evaluator,
            PlatformTransactionManager transactions, ModuleEventNoticeRelayService relay) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        withoutCallerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        withoutCallerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** 不変 descriptor の読取。現在の配送許可を証明する戻り値ではない。 */
    public Optional<ModuleEventNoticeDescriptor> readDescriptor(long noticeRef) {
        requireReference(noticeRef);
        return withoutCallerTransaction.execute(status -> reads.snapshot(noticeRef)
                .map(ModuleEventNoticeReadService.Snapshot::getDescriptor));
    }

    /** 未評価・不正版・不適格 status は empty、正常な受信者0件と旧 phase 終端は of(emptyList)。 */
    public Optional<List<ModuleEventNoticeRecipient>> nextRecipients(long noticeRef, long afterUserId, int limit) {
        requireReference(noticeRef);
        if (afterUserId < 0 || limit < 1 || limit > 500) {
            throw new IllegalArgumentException("受信者 cursor または上限が不正");
        }
        return withoutCallerTransaction.execute(status -> recipients(noticeRef,
                descriptor -> evaluator.nextRecipients(descriptor, afterUserId, limit)));
    }

    /** 有界候補を現在再検査する。業務 close 評価後も別 M 読取で pending / key / status を再確認する。 */
    public Optional<List<ModuleEventNoticeRecipient>> eligibleRecipients(long noticeRef, List<Long> candidateUserIds) {
        requireReference(noticeRef);
        if (candidateUserIds == null || candidateUserIds.size() > 500) {
            throw new IllegalArgumentException("受信者候補の上限が不正");
        }
        if (candidateUserIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("受信者候補 ID は null 不可");
        }
        List<Long> candidates = List.copyOf(candidateUserIds);
        if (candidates.stream().anyMatch(id -> id <= 0)
                || new HashSet<>(candidates).size() != candidates.size()) {
            throw new IllegalArgumentException("受信者候補 ID が不正または重複");
        }
        return withoutCallerTransaction.execute(status -> recipients(noticeRef,
                descriptor -> evaluator.eligibleRecipients(descriptor, candidates)));
    }

    /** 復旧待ちを有界評価した後、最大 limit 件を通常 relay の独立 TX で claim する。 */
    public List<ModuleEventNoticeClaim> claim(int limit, Instant now) {
        Instant nextAttemptAt = Objects.requireNonNull(now, "now").plusSeconds(60);
        return withoutCallerTransaction.execute(status -> {
            for (ModuleEventNoticeClaim claim : relay.claimRecovery(limit, now)) {
                Optional<ModuleEventNoticeReadService.Snapshot> found = claim.getDescriptor().isEmpty()
                        ? Optional.empty() : reads.snapshot(claim.getNoticeRef());
                if (found.isEmpty() || !"WAITING_RECOVERY".equals(found.get().getStatus())
                        || found.get().isPending()
                        || found.get().getDescriptor().getPhase() != ModuleEventNoticePhase.RECOVERED
                        || !sameDescriptor(claim.getDescriptor().orElseThrow(), found.get().getDescriptor())) {
                    relay.deferRecovery(claim.getId(), claim.getClaimToken(), nextAttemptAt, now);
                    continue;
                }
                ModuleEventRecoveryState recovery;
                try {
                    // claim と snapshot の M TX を閉じ、caller TX も停止した状態だけで業務を評価する。
                    recovery = evaluator.recoveryState(found.get().getDescriptor().getImpact());
                } catch (RuntimeException failure) {
                    // 業務 SPI の障害だけを先送りする。DB/read/apply の例外は握りつぶさない。
                    relay.deferRecovery(claim.getId(), claim.getClaimToken(), nextAttemptAt, now);
                    continue;
                }
                if (recovery == ModuleEventRecoveryState.CLOSED_COMPLETE) {
                    relay.applyRecoveryEvaluation(claim.getId(), claim.getClaimToken(), recovery, nextAttemptAt, now);
                } else {
                    relay.deferRecovery(claim.getId(), claim.getClaimToken(), nextAttemptAt, now);
                }
            }
            return relay.claim(limit, now);
        });
    }

    /** RELAYED は enqueue 受付のみ。現在 RELAYING の同じ世代だけを独立 TX で変更する。 */
    public boolean markRelayed(UUID id, UUID claimToken, Instant now) {
        return relay.markRelayed(id, claimToken, now);
    }

    /** 自由文は受け取らず RELAY_FAILED 固定。独立 TX で現在世代だけを失敗記録する。 */
    public boolean markFailed(UUID id, UUID claimToken, Instant nextAttemptAt, boolean dead, Instant now) {
        return relay.markFailed(id, claimToken, nextAttemptAt, dead, now);
    }

    /** 未知版を元の版・試行回数のまま独立 TX で先送りする。 */
    public boolean deferUnsupportedVersion(UUID id, UUID claimToken, Instant nextAttemptAt, Instant now) {
        return relay.deferUnsupportedVersion(id, claimToken, nextAttemptAt, now);
    }

    /** 独立 TX で期限切れ claim を最大100件回収する。 */
    public int recoverStuck(Instant claimedBefore, Instant now) {
        return relay.recoverStuck(claimedBefore, now);
    }

    /** 独立読取 TX で PENDING の最古を返す。強い retention gate は別ロット。 */
    public Optional<Instant> oldestPendingCreatedAt() {
        return relay.oldestPendingCreatedAt();
    }

    private Optional<List<ModuleEventNoticeRecipient>> recipients(long noticeRef,
            Function<ModuleEventNoticeDescriptor, Optional<List<ModuleEventNoticeRecipient>>> delivery) {
        Optional<ModuleEventNoticeReadService.Snapshot> found = reads.snapshot(noticeRef);
        if (found.isEmpty() || !deliverable(found.get().getStatus())) {
            return Optional.empty();
        }
        ModuleEventNoticeReadService.Snapshot snapshot = found.get();
        ModuleEventNoticeDescriptor descriptor = snapshot.getDescriptor();
        ModuleEventRecoveryState recovery = evaluator.recoveryState(descriptor.getImpact());
        if (recovery == ModuleEventRecoveryState.UNKNOWN) {
            return Optional.empty();
        }
        if (recovery == ModuleEventRecoveryState.CLOSED_COMPLETE) {
            // 業務評価中に追加された publication や削除を、別の M 読取 TX で検出する。
            Optional<ModuleEventNoticeReadService.Snapshot> current = reads.snapshot(noticeRef);
            if (current.isEmpty() || !deliverable(current.get().getStatus()) || current.get().isPending()
                    || !sameDescriptor(descriptor, current.get().getDescriptor())) {
                return Optional.empty();
            }
            return descriptor.getPhase() == ModuleEventNoticePhase.RECOVERED
                    ? delivery.apply(current.get().getDescriptor()) : Optional.of(List.of());
        }
        return descriptor.getPhase() == ModuleEventNoticePhase.RECOVERED
                ? Optional.empty() : delivery.apply(descriptor);
    }

    private static boolean deliverable(String status) {
        return "PENDING".equals(status) || "RELAYING".equals(status) || "RELAYED".equals(status);
    }

    private static void requireReference(long noticeRef) {
        if (noticeRef <= 0) {
            throw new IllegalArgumentException("通知参照 ID は正数が必要");
        }
    }

    private static boolean sameDescriptor(ModuleEventNoticeDescriptor left, ModuleEventNoticeDescriptor right) {
        ModuleEventImpactBinding a = left.getImpact();
        ModuleEventImpactBinding b = right.getImpact();
        return left.getNoticeKey().equals(right.getNoticeKey()) && left.getNoticeRef().equals(right.getNoticeRef())
                && left.getPhase() == right.getPhase() && left.getAudience() == right.getAudience()
                && a.getResolverKey().equals(b.getResolverKey()) && a.getOperationId().equals(b.getOperationId())
                && a.getEpisodeId().equals(b.getEpisodeId()) && a.getScopeType() == b.getScopeType()
                && a.getScopeId().equals(b.getScopeId()) && Objects.equals(a.getOrganizationId(), b.getOrganizationId());
    }
}
