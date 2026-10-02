package com.mannschaft.app.repairplan.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.repairplan.entity.TeamMemberTerm;
import com.mannschaft.app.repairplan.repository.TeamMemberTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 任期終了降格バッチ（F08.8 Phase 5）。
 *
 * <p>毎晩 23:00 JST に起動し、90 日以上前に任期終了を過ぎたアクティブフラグを持つ
 * 理事の {@code is_active} を {@code false} に変更する。
 * チームロールの降格（ADMIN → MEMBER）は将来フェーズで MembershipService 経由で行う予定。</p>
 *
 * <p>テスト可能なよう {@link #executeAt(LocalDate)} で日付注入できる設計にしている。</p>
 *
 * <p>TODO: repairplan ドメインと auth ドメインをまたいでいる。
 * 将来は TeamMemberTermExpiredEvent で分離予定。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamMemberTermDemoteBatch {

    /** 任期終了から何日後に降格対象とするか。 */
    private static final int DEMOTE_GRACE_DAYS = 90;

    private final TeamMemberTermRepository termRepository;
    private final AuditLogService auditLogService;

    /**
     * スケジュール起動エントリポイント（毎晩 23:00 JST）。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_PROPERTY_REPAIRPLAN_ENABLED",
            reason = "任期終了 90 日経過という時刻条件で冪等に判定でき、修繕計画機能を閉じている間は理事の権限が行使される画面自体が閉じている")
    @BatchEndpoint(name = "repairplan-team-member-term-demote-daily", description = "任期終了 90 日経過の理事を毎日 23:00 に非アクティブ化する")
    @Scheduled(cron = "0 0 23 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "TeamMemberTermDemoteBatch", lockAtMostFor = "PT55M")
    @Transactional
    public void execute() {
        executeAt(LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")));
    }

    /**
     * テスト可能な実装本体。対象日付を引数で受け取る。
     *
     * @param today 基準日（JST 今日）
     */
    @Transactional
    void executeAt(LocalDate today) {
        // 90日以上前に term_end を過ぎた is_active=true の任期を取得
        LocalDate threshold = today.minusDays(DEMOTE_GRACE_DAYS);
        List<TeamMemberTerm> targets = termRepository.findByIsActiveTrueAndTermEndBefore(threshold);

        if (targets.isEmpty()) {
            log.debug("任期降格バッチ: 対象なし (today={}, threshold={})", today, threshold);
            return;
        }

        int demoted = 0;
        for (TeamMemberTerm term : targets) {
            try {
                term.setIsActive(false);
                termRepository.save(term);
                // TODO: 将来フェーズで MembershipService.demoteToMember(term.getUserId(), term.getScopeId()) を呼ぶ
                // 現在は is_active フラグのみ変更し、チームロール降格は後続フェーズで実施
                demoted++;
                log.info("任期降格: termId={}, userId={}, scopeId={}, termEnd={}",
                        term.getId(), term.getUserId(), term.getScopeId(), term.getTermEnd());
            } catch (Exception e) {
                log.error("任期降格失敗: termId={}, userId={}", term.getId(), term.getUserId(), e);
            }
        }

        log.info("任期降格バッチ完了: 対象{}件, 降格{}件 (today={})", targets.size(), demoted, today);
        auditLogService.record("TEAM_MEMBER_TERM_DEMOTE_BATCH", null, null, null, null, null, null, null,
                String.format("{\"targets\":%d,\"demoted\":%d,\"today\":\"%s\"}", targets.size(), demoted, today));
    }
}
