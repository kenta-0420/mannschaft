package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集型予約: リマインド通知バッチ (Phase 2)。
 *
 * <p>毎分実行し、{@code sent_at IS NULL AND remind_at <= NOW()} のリマインダーを
 * 最大100件処理して {@code RECRUITMENT_REMINDER} 通知を送信する。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット2 による是正</h2>
 * <p>是正前は<b>バッチ全体を 1 つの {@code @Transactional} で包みながら 1 件ずつ catch</b> していた。
 * 1 件の失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * <b>全件の {@code sent_at} 更新がコミット時に巻き戻り</b>、1 分後の再実行で全員へ二重リマインドが
 * 飛びうる状態だった。非トランザクションのオーケストレータ ＋ リマインダーごと
 * {@link RecruitmentReminderRunner}（{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ
 * 是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code recruitment_reminders.sent_at}）を更新する</b>。
 * この列は「毎分の抽出条件そのもの」であり二重送信を防ぐ冪等キーであるため、通知と同時に
 * 確定しなければならない。よって確定設計の「バッチで業務状態も更新する」に該当し、
 * 非TXループ → 項目ごと REQUIRES_NEW → その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code reminderBatch} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint} 経由の
 * 管理コンソール実行も戻り値を持たないため、FE / OpenAPI への波及はない。
 * ログ上の「成功」は<b>「通知の到達件数」ではなく「リマインドを確定した件数」</b>を意味するようになった
 * （非同期化により、バッチ終了時点では配送結果が判明しないため）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentReminderBatch {

    /** 1 回のバッチで処理する最大件数。
     * fixedDelay=1分・上限100件で、通常の発生量に対して十分な処理能力を持つ。 */
    private static final int BATCH_SIZE = 100;

    private final RecruitmentReminderRepository reminderRepository;
    private final RecruitmentReminderRunner recruitmentReminderRunner;

    /**
     * 送信すべきリマインダーを処理する。
     * {@code fixedDelay = 60_000} ms = 1分間隔（前回実行完了から1分後に次の実行）。
     *
     * <p>対象の絞り込みは {@link RecruitmentReminderRepository#findSendableReminders} が行う。
     * <b>既に開始した募集は対象外</b>であり、長期停止から再開しても
     * 過去の募集へ「24時間後に開催」を一斉送信しない。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_RECRUITMENT_ENABLED",
            reason = "止まるのは開始前リマインドの送信のみで DB の募集データは書き換えない。募集機能を閉じている間は通知を受け取る画面も閉じており、再開時に古い分を吐き出さないことはクエリ側（開始済みの募集を対象外にする下限）で保証している")
    @BatchEndpoint(name = "recruitment-reminder", description = "募集型予約の未送信リマインドを毎分処理する")
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "recruitment-reminder-batch",
            lockAtLeastFor = "PT50S",
            lockAtMostFor = "PT2M")
    public void reminderBatch() {
        LocalDateTime now = LocalDateTime.now();

        // 対象抽出はオーケストレータ側（TX 外）。以降の更新はここには参加しない。
        List<RecruitmentReminderEntity> pending =
                reminderRepository.findSendableReminders(now, PageRequest.of(0, BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.info("F03.11 リマインダーバッチ開始: 対象件数={}", pending.size());
        int successCount = 0;
        int failCount = 0;
        Long firstFailedReminderId = null;

        for (RecruitmentReminderEntity reminder : pending) {
            Long reminderId = reminder.getId();
            try {
                if (recruitmentReminderRunner.processOne(reminderId)) {
                    successCount++;
                }
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                failCount++;
                if (firstFailedReminderId == null) {
                    firstFailedReminderId = reminderId;
                }
                log.error("F03.11 リマインド確定に失敗（継続）: reminderId={}", reminderId, e);
            }
        }

        String summary = "F03.11 リマインダーバッチ完了: 対象={}, 確定={}, 失敗={}, firstFailedReminderId={}";
        if (failCount > 0) {
            log.error(summary, pending.size(), successCount, failCount, firstFailedReminderId);
        } else {
            log.info(summary, pending.size(), successCount, failCount, firstFailedReminderId);
        }
    }
}
