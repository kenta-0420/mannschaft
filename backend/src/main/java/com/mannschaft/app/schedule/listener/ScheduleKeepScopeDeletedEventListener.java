package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * チーム／組織の削除に伴うキープの後始末（F03.17 §3.7・AC-27b）。
 *
 * <h2>なぜアプリ層で消す必要があるのか（§3.5.1）</h2>
 * <p>{@code schedule_keeps.team_id} / {@code organization_id} は<b>クロスドメイン参照なので FK を張っていない</b>
 * （原則1）。加えて {@code TeamService.deleteTeam} は親行を<b>論理削除</b>するだけであり、
 * 親行は物理的に残る。したがって <b>DB の CASCADE は二重の意味で発火しない</b>——
 * FK が無いので定義自体が無く、あったとしても親が消えていないので走らない。
 * ここで後始末をしなければ、消えたチームのキープが永久に dangling で残る。</p>
 *
 * <h2>実装様式は {@code MembershipEventListener} を踏襲する</h2>
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW} + try/catch で警告ログ。
 * {@code AFTER_COMMIT} なのは、チーム削除がロールバックされたときにキープだけ消えるのを防ぐため。
 * {@code REQUIRES_NEW} なのは、素の {@code REQUIRED} が {@code AFTER_COMMIT} フェーズでは
 * 既にコミット済みの TX に参加できず silently 破棄されるため（必須であって好みではない）。
 * 例外は握るが<b>ログには必ず残す</b>——握りつぶしではなく、他ドメインのリスナーを巻き添えにしない隔離である。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleKeepScopeDeletedEventListener {

    private final ScheduleKeepRepository scheduleKeepRepository;

    /**
     * チーム削除時、そのチームスコープのキープを論理削除する。
     *
     * @param event チーム削除イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チーム・組織の削除に伴う予定キープの後始末。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamDeleted(TeamDeletedEvent event) {
        try {
            int deleted = softDeleteAll(scheduleKeepRepository.findAllByTeamId(event.getTeamId()));
            log.info("チーム削除: キープを論理削除しました: teamId={}, deleted={}", event.getTeamId(), deleted);
        } catch (Exception ex) {
            log.warn("チーム削除に伴うキープ後始末に失敗: teamId={}, error={}",
                    event.getTeamId(), ex.getMessage(), ex);
        }
    }

    /**
     * 組織削除時、その組織スコープのキープを論理削除する。
     *
     * @param event 組織削除イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チーム・組織の削除に伴う予定キープの後始末。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationDeleted(OrganizationDeletedEvent event) {
        try {
            int deleted = softDeleteAll(
                    scheduleKeepRepository.findAllByOrganizationId(event.getOrganizationId()));
            log.info("組織削除: キープを論理削除しました: organizationId={}, deleted={}",
                    event.getOrganizationId(), deleted);
        } catch (Exception ex) {
            log.warn("組織削除に伴うキープ後始末に失敗: organizationId={}, error={}",
                    event.getOrganizationId(), ex.getMessage(), ex);
        }
    }

    /**
     * 与えられたキープを論理削除する（原則3）。
     *
     * <p>{@code findAllBy*} は {@code @SQLRestriction("deleted_at IS NULL")} により
     * 既に論理削除済みの行を返さないため、二重削除にはならない（再実行しても安全）。</p>
     */
    private int softDeleteAll(List<ScheduleKeepEntity> keeps) {
        if (keeps.isEmpty()) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now();
        keeps.forEach(keep -> keep.setDeletedAt(now));
        scheduleKeepRepository.saveAll(keeps);
        return keeps.size();
    }
}
