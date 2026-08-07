package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.ParticipantHistoryReason;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F03.11 Phase 3: 自動キャンセルバッチ (§5.4)。
 *
 * <p>自動キャンセル日時 (auto_cancel_at) を過ぎており、かつ最小定員 (min_capacity) を
 * 満たせなかった OPEN/FULL 状態の募集を AUTO_CANCELLED に遷移させる。</p>
 *
 * <p>ShedLock による分散ロックで多重起動を防止する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentAutoCancelBatch {

    /** 参加者チャンク処理のページサイズ。 */
    private static final int CHUNK_SIZE = 100;

    /**
     * 参加者キャンセルループの最大反復回数（安全弁）。
     * CHUNK_SIZE(100) × 1000 = 最大10万件/募集まで処理する。
     */
    private static final int MAX_ITERATIONS = 1000;

    /** 自動キャンセル対象の参加者ステータス。 */
    private static final List<RecruitmentParticipantStatus> CANCEL_TARGET_STATUSES = List.of(
            RecruitmentParticipantStatus.CONFIRMED,
            RecruitmentParticipantStatus.WAITLISTED,
            RecruitmentParticipantStatus.APPLIED
    );

    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentParticipantHistoryRepository historyRepository;
    private final ConfirmableNotificationService confirmableNotificationService;

    /**
     * 5分間隔で自動キャンセル対象の募集を処理する。
     */
    @BatchEndpoint(name = "recruitment-auto-cancel", description = "auto_cancel_at 経過かつ最小定員不足の募集を 5 分毎に AUTO_CANCELLED に遷移する")
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @SchedulerLock(name = "recruitment-auto-cancel-batch", lockAtLeastFor = "PT4M", lockAtMostFor = "PT15M")
    public void run() {
        LocalDateTime now = LocalDateTime.now();
        List<RecruitmentListingEntity> candidates = listingRepository.findAutoCancelTargets(now);

        if (candidates.isEmpty()) {
            return;
        }

        log.info("F03.11 自動キャンセルバッチ開始: 候補件数={}", candidates.size());

        int totalCancelled = 0;
        for (RecruitmentListingEntity candidate : candidates) {
            try {
                int result = processSingleListing(candidate.getId(), now);
                totalCancelled += result;
            } catch (Exception e) {
                log.warn("F03.11 自動キャンセルバッチ 個別処理失敗: listingId={}, error={}",
                        candidate.getId(), e.getMessage());
            }
        }
        log.info("F03.11 自動キャンセルバッチ完了: 処理件数={}", totalCancelled);
    }

    /**
     * 1つの募集を自動キャンセルする。トランザクション分離で大量ロック回避。
     *
     * <p>参加者のキャンセル処理は、対象ステータスで絞り込んだ先頭ページ（page=0固定）を
     * 繰り返し取り直す「縮小キューのドレイン」方式で行う。キャンセル済みの行は次回抽出から
     * 自然に外れるため、オフセットを前進させてはならない（{@link
     * com.mannschaft.app.payment.batch.PaymentRequestOverdueBatchService#execute()} と同型）。</p>
     *
     * <p>以下のいずれかに達すると安全弁が働き、WARN ログを出力してループを中断する
     * （残りは次回バッチ実行で再試行される想定）:</p>
     * <ul>
     *     <li>最大反復回数（{@value #MAX_ITERATIONS} 回、CHUNK_SIZE={@value #CHUNK_SIZE} 件/回）に到達</li>
     *     <li>1反復で1件も処理が進まなかった（ステータス遷移が反映されない等の異常時）</li>
     * </ul>
     *
     * @param listingId 処理対象の募集ID
     * @param now       バッチ実行日時
     * @return キャンセルした参加者数（0はスキップ含む。安全弁で中断した場合も途中までの処理数を返す）
     */
    @Transactional
    public int processSingleListing(Long listingId, LocalDateTime now) {
        // PESSIMISTIC_WRITE で行ロックを取得して最新状態を確認
        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new IllegalStateException("募集が見つかりません: id=" + listingId));

        // 再確認: OPEN/FULL かつ confirmedCount < minCapacity であること
        if (listing.getStatus() != RecruitmentListingStatus.OPEN
                && listing.getStatus() != RecruitmentListingStatus.FULL) {
            log.debug("F03.11 自動キャンセルスキップ (既にステータス変更済み): listingId={}, status={}",
                    listingId, listing.getStatus());
            return 0;
        }
        if (listing.getConfirmedCount() >= listing.getMinCapacity()) {
            log.debug("F03.11 自動キャンセルスキップ (最小定員達成済み): listingId={}, confirmed={}, min={}",
                    listingId, listing.getConfirmedCount(), listing.getMinCapacity());
            return 0;
        }

        // 募集を AUTO_CANCELLED に遷移
        listing.autoCancel();

        // 参加者を 100件/チャンクでキャンセル処理。
        //
        // 【重要】ここで抽出クエリ findByListingIdAndStatusIn は CANCEL_TARGET_STATUSES で
        // 絞り込んでいるが、ループ内で各行の status を CANCELLED に変えるため、処理済みの行は
        // 次回のクエリ結果から自然に外れる（対象集合が縮んでいく "drain" 型のループ）。
        // そのため PageRequest のオフセット（page番号）は絶対に進めてはならない
        // （常に PageRequest.of(0, CHUNK_SIZE) で先頭ページを取り直す）。
        // もし page++ のようにオフセットを前進させると、集合が縮んでいるのに読み取り位置だけ
        // 前進することになり、後続の参加者を読み飛ばしてキャンセル漏れが発生する
        // （PaymentRequestOverdueBatchService と同型のバグパターン。同クラスの execute() を参照）。
        List<Long> affectedUserIds = new ArrayList<>();
        int totalProcessed = 0;
        int iteration = 0;
        // 安全弁: ステータス遷移が期待どおり効かない等の異常時に同じ行を繰り返し取得し続け
        // 無限ループに陥らないよう、処理済み参加者IDを記録して「進捗ゼロ」を検知する。
        Set<Long> processedParticipantIds = new HashSet<>();

        while (true) {
            if (iteration >= MAX_ITERATIONS) {
                log.warn("F03.11 自動キャンセル 参加者キャンセルループが上限反復回数に到達したため中断: "
                                + "listingId={}, 上限反復回数={}, 処理済み件数={}",
                        listingId, MAX_ITERATIONS, totalProcessed);
                break;
            }

            // 常に先頭ページ（page=0）を取り直す。上のコメント参照。
            Page<RecruitmentParticipantEntity> chunk = participantRepository.findByListingIdAndStatusIn(
                    listingId, CANCEL_TARGET_STATUSES, PageRequest.of(0, CHUNK_SIZE));

            if (chunk.isEmpty()) {
                break;
            }

            boolean progressed = false;
            for (RecruitmentParticipantEntity participant : chunk.getContent()) {
                if (!processedParticipantIds.add(participant.getId())) {
                    // 既に処理済みの参加者が再度抽出された（ステータス遷移が反映されていない等の異常）。
                    // 二重処理を避けるためスキップする。
                    continue;
                }

                RecruitmentParticipantStatus oldStatus = participant.getStatus();

                // 参加者をシステムキャンセル
                participant.cancelBySystem();
                participantRepository.save(participant);

                // 参加者履歴を作成
                RecruitmentParticipantHistoryEntity history = RecruitmentParticipantHistoryEntity.builder()
                        .participantId(participant.getId())
                        .listingId(listingId)
                        .oldStatus(oldStatus)
                        .newStatus(RecruitmentParticipantStatus.CANCELLED)
                        .changedBy(null) // システム操作のため null
                        .changeReason(ParticipantHistoryReason.AUTO_CANCEL)
                        .build();
                historyRepository.save(history);

                // 通知対象ユーザーIDを収集
                if (participant.getUserId() != null) {
                    affectedUserIds.add(participant.getUserId());
                }
                totalProcessed++;
                progressed = true;
            }

            if (!progressed) {
                // 抽出条件で絞ったはずの行が、キャンセル済み扱いにならず同じ内容で返り続けている。
                // このまま回すと無限ループになるため中断し、残件数を明示して報告する。
                log.warn("F03.11 自動キャンセル 参加者キャンセルループで進捗ゼロを検知したため中断: "
                                + "listingId={}, 処理済み件数={}, 未処理推定件数={}",
                        listingId, totalProcessed, chunk.getTotalElements());
                break;
            }

            iteration++;
        }

        // 募集を保存
        listingRepository.save(listing);

        log.info("F03.11 自動キャンセル実行: listingId={}, 参加者キャンセル数={}", listingId, totalProcessed);

        // 通知送信（受信者が存在する場合のみ）
        if (!affectedUserIds.isEmpty()) {
            try {
                confirmableNotificationService.send(
                        ScopeType.valueOf(listing.getScopeType().name()),
                        listing.getScopeId(),
                        "募集が自動キャンセルされました",
                        "最小定員を達成できなかったため自動キャンセルされました",
                        ConfirmableNotificationPriority.URGENT,
                        LocalDateTime.now().plusHours(72),
                        null, null, null, null,
                        null,
                        affectedUserIds);
            } catch (Exception e) {
                log.warn("F03.11 自動キャンセル通知送信失敗: listingId={}, error={}", listing.getId(), e.getMessage());
            }
        }

        return totalProcessed;
    }
}
