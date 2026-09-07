package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.event.ScheduleKeepConvertedEvent;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * キープ変換通知の配送リスナー（F03.17 §6 / Issue #2990 L8 ROLLBACK_COUPLED 是正）。
 *
 * <h2>是正前の欠陥 — 「REQUIRES_NEW で守った」は半分だけだった</h2>
 * <p>是正前は {@code ScheduleKeepService#convert}（{@code @Transactional}）が
 * {@code ScheduleKeepNotificationService#notifyConverted} を同期で呼び、失敗を
 * {@code try/catch} で握って「変換自体は成立」とログしていた。通知の永続化だけは
 * {@code ScheduleKeepNotificationPublisher}（{@code REQUIRES_NEW}）へ逃がしてあり、
 * その javadoc は<b>これで変換は巻き戻らないと宣言していた</b>。しかし同じメソッドの中で
 * TEAM スコープのとき実行される {@code NotificationFanoutJobService#enqueue}（fan-out 親ジョブの
 * INSERT）と、宛先判定の {@code ContentVisibilityChecker#canViewUuid}（SELECT）は
 * <b>外側の業務トランザクションのまま</b>だった。enqueue が DB 例外で落ちると rollback-only が立ち、
 * catch は「変換自体は成立」という<b>嘘のログ</b>を残し、直後のコミットが
 * {@code UnexpectedRollbackException} になって<b>キープの SCHEDULED 化と変換先予定ごと失われる</b>。
 * 通知経路の一部だけを別トランザクションへ逃がしても、残りが業務TXに残っていれば守れていない
 * ——という #2990 で繰り返し出た形である。</p>
 *
 * <h2>是正後</h2>
 * <p>{@code ScheduleKeepService#convert} は {@link ScheduleKeepConvertedEvent}（ID のみ）を
 * publish するだけに留め、本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で
 * 受け取る。可視性判定・直送・fan-out enqueue はいずれも業務コミット後に実行されるため、
 * どれが失敗しても変換は既に確定している。</p>
 *
 * <h2>{@code ScheduleKeepNotificationPublisher} を廃止した理由</h2>
 * <p>あの Bean が存在した唯一の理由は「業務TXの rollback-only 汚染を避けるため通知の永続化だけを
 * {@code REQUIRES_NEW} へ逃がす」ことであり、その代償として<b>内側 TX からは外側の未コミット
 * データが見えないので一切再検索できない</b>という強い制約を背負っていた。配送そのものが
 * コミット後に移った今、汚染すべき業務TXは存在せず、再検索も自由にできる。役目を終えた迂回路を
 * 残すと「なぜ REQUIRES_NEW なのか」を後任が読み違えるため削除した。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleKeepConvertedNotificationListener {

    private final ScheduleKeepRepository scheduleKeepRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleKeepNotificationService scheduleKeepNotificationService;

    /**
     * キープ変換イベントを受け取り、作成者への直送と TEAM 全員への fan-out を発行する。
     *
     * @param event キープ変換イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "キープ変換通知は F03.17 §2.1.1 の代償として設計上必須である（変換は MEMBER 全員に"
                    + "開放されており、通知を落とすと作成者は自分のキープが予定になったことを知り得ない）。"
                    + "棚卸し台帳に停止用の gate_key を持たず、イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleKeepConverted(ScheduleKeepConvertedEvent event) {
        if (event.keepId() == null || event.convertedScheduleId() == null) {
            return;
        }
        try {
            ScheduleKeepEntity keep = scheduleKeepRepository.findById(event.keepId()).orElse(null);
            ScheduleEntity schedule = scheduleRepository.findById(event.convertedScheduleId()).orElse(null);
            if (keep == null || schedule == null) {
                // 変換直後にキープまたは予定が削除された等。読み直せない以上、本文も宛先も作れない。
                log.warn("キープ変換通知の読み直しで対象が見つからないため配送を中止: keepId={}, scheduleId={}",
                        event.keepId(), event.convertedScheduleId());
                return;
            }
            scheduleKeepNotificationService.notifyConverted(
                    scopeOf(keep), keep, schedule, event.actorUserId());
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TXの外なので rollback で消えない。
            log.error("キープ変換通知の配送に失敗しました（変換は既にコミット済み）: keepId={}, scheduleId={}",
                    event.keepId(), event.convertedScheduleId(), e);
        }
    }

    /**
     * キープのスコープ列からスコープを復元する。
     *
     * <p>{@code ScheduleKeepAccessGuard} がパスのスコープとレコードのスコープ列の一致を強制している
     * ため（§4.6.3）、変換時に使われたスコープと同じものが復元される。TEAM のキープは所属組織の
     * {@code organization_id} も併せ持ちうるので、判定は TEAM を先に見る。</p>
     */
    private ScheduleKeepScope scopeOf(ScheduleKeepEntity keep) {
        if (keep.getTeamId() != null) {
            return ScheduleKeepScope.team(keep.getTeamId());
        }
        if (keep.getOrganizationId() != null) {
            return ScheduleKeepScope.organization(keep.getOrganizationId());
        }
        return ScheduleKeepScope.personal(keep.getUserId());
    }
}
