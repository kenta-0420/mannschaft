package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
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
 * 退会（匿名化）に伴うキープの後始末（F03.17 §3.7・AC-27）。
 *
 * <h2>個人スコープは消し、チーム／組織スコープは残す</h2>
 * <p>この非対称が本リスナーの要点である。</p>
 * <ul>
 *   <li><b>個人スコープ（{@code user_id} = 退会者）は論理削除する。</b>
 *       「行きたいことリスト」は本人以外に価値が無い私的メモであり、残す理由が無い。</li>
 *   <li><b>チーム／組織スコープのキープは残置する。</b> これはチームの資産である。
 *       消すと<b>他のメンバーの相談履歴まで失われる</b>（「夏合宿どうする？」が、
 *       言い出した人が辞めた途端に消える）。{@code created_by} の表示解決だけが
 *       匿名化ユーザーの表示規約に従う（{@code NameResolverService} が担う）ため、
 *       ここでキープ本体に手を入れる必要はない。</li>
 * </ul>
 *
 * <h2>{@code fallbackExecution = true} を付けている理由</h2>
 * <p>{@link UserAnonymizedEvent} は退会フローのトランザクション内から発行されるのが通常だが、
 * 運用リカバリや再処理など<b>トランザクション外から発行される経路もありうる</b>。
 * 既定（{@code false}）だと、そのときリスナーは<b>例外も出さずに何もしない</b>——
 * PII が消えていないのに成功したように見える、最も危険な失敗の仕方をする。
 * {@code fallbackExecution = true} なら、トランザクションが無い場合は即時実行される。</p>
 *
 * <p>{@code @Async} は付けない。退会時の PII 消去は「いつの間にか終わっている」ことを期待してよい処理ではなく、
 * 発行元のスレッドで完了まで見届けたほうが失敗を検知できる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleKeepAnonymizationEventListener {

    private final ScheduleKeepRepository scheduleKeepRepository;

    /**
     * 退会即時匿名化を購読し、個人スコープのキープを論理削除する。
     *
     * @param event 退会即時匿名化イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者のスケジュールキープ情報に個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            List<ScheduleKeepEntity> personalKeeps = scheduleKeepRepository.findAllByUserId(userId);
            if (!personalKeeps.isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                personalKeeps.forEach(keep -> keep.setDeletedAt(now));
                scheduleKeepRepository.saveAll(personalKeeps);
            }
            log.info("ユーザー退会: 個人スコープのキープを論理削除しました: userId={}, deleted={}",
                    userId, personalKeeps.size());
        } catch (Exception ex) {
            log.warn("ユーザー退会に伴う個人キープの後始末に失敗: userId={}, error={}",
                    userId, ex.getMessage(), ex);
        }
    }
}
