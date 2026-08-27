package com.mannschaft.app.schedule.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.10 §5.8: 代理出席の孤立委任クリーンアップ日次バッチ（スケジュール + イベント）。
 *
 * <p>{@code MembershipEndedEvent} リスナー（{@code ScheduleDelegationMembershipListener} /
 * {@code EventDelegationMembershipListener}）が主機構として退会時に即座に取り消すが、
 * イベントの取りこぼし（古いデータ・直接 DB 操作・イベント未発火経路）を補完するための日次バッチである。</p>
 *
 * <p>判定: アクティブ（PENDING/ACCEPTED）委任のうち、委任者または代理人が当該スコープに
 * アクティブメンバーシップを持たないものを「孤立」とみなし CANCELLED にする。
 * メンバーシップ判定は {@link MembershipRepository}（F00.5 基盤）で行う（読み取りのみのクロスドメイン参照）。</p>
 *
 * <p>ShedLock により複数インスタンス起動時も重複実行を防ぐ。手本: {@code ActionMemoReminderBatchService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyDelegationCleanupBatchService {

    private final ScheduleDelegationService scheduleDelegationService;
    private final EventDelegationService eventDelegationService;
    private final MembershipRepository membershipRepository;

    /**
     * 日次起動エントリポイント（毎日 03:40 JST）。
     */
    @BatchEndpoint(name = "proxy-delegation-cleanup",
            description = "代理出席の孤立委任（当事者がスコープ在籍不可）を日次で CANCELLED にする")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。期限切れ代理権限の整理であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 40 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "proxyDelegationCleanupBatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void execute() {
        int scheduleCancelled = cleanupScheduleDelegations();
        int eventCancelled = cleanupEventDelegations();
        log.info("代理出席孤立委任クリーンアップ完了: schedule={}, event={}", scheduleCancelled, eventCancelled);
    }

    /**
     * スケジュール側の孤立委任を取り消す。
     *
     * @return 取り消した件数
     */
    @Transactional
    public int cleanupScheduleDelegations() {
        List<ScheduleDelegationEntity> active = scheduleDelegationService.findAllActive();
        int cancelled = 0;
        for (ScheduleDelegationEntity d : active) {
            ScopeType scopeType = d.getOrganizationId() != null ? ScopeType.ORGANIZATION : ScopeType.TEAM;
            Long scopeId = d.getOrganizationId() != null ? d.getOrganizationId() : d.getTeamId();
            if (scopeId == null) {
                continue;
            }
            boolean delegatorActive = membershipRepository.existsActiveByUserAndScope(d.getDelegatorId(), scopeType, scopeId);
            boolean delegateActive = membershipRepository.existsActiveByUserAndScope(d.getDelegateId(), scopeType, scopeId);
            if (!delegatorActive || !delegateActive) {
                Long leftUserId = !delegateActive ? d.getDelegateId() : d.getDelegatorId();
                scheduleDelegationService.cancelOnMemberLeft(d, leftUserId);
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * イベント側の孤立委任を取り消す。
     *
     * @return 取り消した件数
     */
    @Transactional
    public int cleanupEventDelegations() {
        List<EventDelegationEntity> active = eventDelegationService.findAllActive();
        int cancelled = 0;
        for (EventDelegationEntity d : active) {
            ScopeType scopeType = d.getOrganizationId() != null ? ScopeType.ORGANIZATION : ScopeType.TEAM;
            Long scopeId = d.getOrganizationId() != null ? d.getOrganizationId() : d.getTeamId();
            if (scopeId == null) {
                continue;
            }
            boolean delegatorActive = membershipRepository.existsActiveByUserAndScope(d.getDelegatorId(), scopeType, scopeId);
            boolean delegateActive = membershipRepository.existsActiveByUserAndScope(d.getDelegateId(), scopeType, scopeId);
            if (!delegatorActive || !delegateActive) {
                Long leftUserId = !delegateActive ? d.getDelegateId() : d.getDelegatorId();
                eventDelegationService.cancelOnMemberLeft(d, leftUserId);
                cancelled++;
            }
        }
        return cancelled;
    }
}
