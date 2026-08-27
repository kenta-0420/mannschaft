package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ポイっとメモ リマインド送信バッチ。
 * 30分ごとに未送信のリマインドを確認し、ユーザー単位で集約して通知する。
 * プライバシー保護: 通知文言にメモタイトル・内容を含めない。
 *
 * <p><b>タイムゾーン:</b> {@code reminder1ScheduledAt} / {@code reminder2ScheduledAt} /
 * {@code reminder3ScheduledAt} は {@link QuickMemoService} で
 * JST（{@code Asia/Tokyo}）基準の {@link LocalDateTime} として保存される。
 * バッチ比較の {@code now} も同じ JST で取得することで基準を統一する。</p>
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット1 による是正</h2>
 * <p>是正前は<b>バッチ全体を 1 つの {@code @Transactional} で包みながらユーザー単位で catch</b> していた。
 * 1 ユーザーの失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * <b>全ユーザーの送信済み記録がコミット時に巻き戻り</b>、次回起動で全員へ二重通知が飛びうる状態だった。
 * 非トランザクションのオーケストレータ ＋ ユーザーごと {@link QuickMemoReminderRunner}
 * （{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code quick_memos.reminderX_sent_at}）を更新する</b>。
 * この列は二重通知を防ぐ冪等キーであり、通知と同時に確定しなければならない。よって確定設計の
 * 「バッチで業務状態も更新する」に該当し、非TXループ → ユーザーごと REQUIRES_NEW →
 * その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>順序のトレードオフ（送信済み記録 → コミット → 通知）</h2>
 * <p>この形では「送信済みを記録してコミットしたのに、その後の通知配送が落ちる」場合に
 * そのユーザーの当該リマインドが失われる。逆に通知を先に出す形にすると、記録の失敗で
 * 30 分後に同じリマインドが再送される。<b>ポイっとメモのリマインドは重複が実害となる通知</b>
 * （ADHD 配慮の導線として通知過多を避ける設計）であるため、確定設計どおり記録先行を採り、
 * 配送失敗はリスナー側の構造化 ERROR ログで観測する。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code execute} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint} 経由の管理コンソール実行も
 * 戻り値を持たないため、FE / OpenAPI への波及はない。監査ログの
 * {@code notifiedUsers} は<b>「通知の送信成功数」ではなく「リマインドを確定した対象ユーザー数」</b>を
 * 意味するようになった（非同期化により、バッチ終了時点では配送結果が判明しないため）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickMemoReminderBatchService {

    private static final int BATCH_LIMIT = 10000;
    /** reminder_xScheduledAt の保存基準と同じ TZ */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final QuickMemoRepository memoRepository;
    private final QuickMemoReminderRunner quickMemoReminderRunner;
    private final AuditLogService auditLogService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。ポイっとメモのリマインド通知送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "quickmemo-reminder-dispatch", description = "ポイっとメモのリマインド通知を 30 分毎にユーザー単位で集約送信する")
    @Scheduled(cron = "0 */30 * * * *")
    // 起動間隔は 30 分。処理はリマインド対象のユーザー単位集約送信で通常は数秒〜数十秒。間隔と同値にすると 1 回の超過で二重通知になるため、
    // 間隔の 2 倍を上限とする。
    @SchedulerLock(name = "quickmemoReminderDispatch", lockAtLeastFor = "PT30S", lockAtMostFor = "PT1H")
    public void execute() {
        // reminder_xScheduledAt は QuickMemoService で JST LocalDateTime として保存されるため
        // 比較用の now も同じ JST で取得する
        LocalDateTime now = LocalDateTime.now(ZONE_JST);
        log.info("リマインドバッチ開始: {}", now);

        // 対象抽出はオーケストレータ側（TX 外）。以降の更新はここには参加しない。
        List<QuickMemoEntity> targets = memoRepository.findReminderTargets(now, PageRequest.of(0, BATCH_LIMIT));
        if (targets.isEmpty()) {
            return;
        }

        // ユーザー単位に集約してリマインドを送信
        Map<Long, List<Long>> memoIdsByUser = targets.stream()
                .collect(Collectors.groupingBy(QuickMemoEntity::getUserId,
                        Collectors.mapping(QuickMemoEntity::getId, Collectors.toList())));

        int notifiedUsers = 0;
        int failed = 0;
        Long firstFailedUserId = null;
        for (Map.Entry<Long, List<Long>> entry : memoIdsByUser.entrySet()) {
            Long userId = entry.getKey();
            try {
                if (quickMemoReminderRunner.markRemindersSent(userId, entry.getValue(), now) > 0) {
                    notifiedUsers++;
                }
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                failed++;
                if (firstFailedUserId == null) {
                    firstFailedUserId = userId;
                }
                log.error("リマインド確定に失敗: userId={}", userId, e);
            }
        }

        String summary = "リマインドバッチ完了: 対象{}件, 対象ユーザー{}, 確定{}ユーザー, 失敗{}, firstFailedUserId={}";
        if (failed > 0) {
            log.error(summary, targets.size(), memoIdsByUser.size(), notifiedUsers, failed, firstFailedUserId);
        } else {
            log.info(summary, targets.size(), memoIdsByUser.size(), notifiedUsers, failed, firstFailedUserId);
        }

        // 監査ログもオーケストレータ側（TX 外）で記録する。バッチを 1 トランザクションで包んでいた
        // 是正前は、どこか 1 件の失敗で rollback-only になると監査記録ごと消えていた。
        auditLogService.record("QUICK_MEMO_REMINDER_BATCH", null, null, null, null, null, null, null,
                "{\"targetMemos\":" + targets.size()
                        + ",\"notifiedUsers\":" + notifiedUsers
                        + ",\"failedUsers\":" + failed + "}");
    }
}
