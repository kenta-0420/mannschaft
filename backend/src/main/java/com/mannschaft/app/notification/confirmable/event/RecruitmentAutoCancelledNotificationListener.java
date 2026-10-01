package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.event.RecruitmentAutoCancelledNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * CMP-260930-1932: 自動キャンセルされた募集の参加者へ、システム発の確認通知を送る
 * （{@link RecruitmentPenaltyAppliedNotificationListener} と同型。AFTER_COMMIT + {@code @Async("event-pool")}）。
 *
 * <p><b>TX境界（backend/.claudecode.md 原則5）</b>: 自動キャンセル本体（{@code RecruitmentAutoCancelBatch
 * #processSingleListing}）の業務TXがコミットされた後に、別スレッド・別TX（{@code sendFromSource} 自身の TX）
 * で送る。送信が失敗しても自動キャンセルは巻き戻らず、ERROR ログ（listingId 入り）で可視化する。</p>
 *
 * <p><b>冪等（AC-5）</b>: 先に {@code existsBySourceTypeAndSourceId} で送信済みを確認して再送しない。
 * 並行2回発火で両方が確認をすり抜けた場合は、{@code confirmable_notifications} の生成列
 * {@code once_per_source_key} の UNIQUE（{@code uq_cn_once_per_source}）が後着側の INSERT を拒否する。
 * 後着側は {@link DataIntegrityViolationException} を受け、送信済みを再確認できれば重複として扱う
 * （ERROR にしない）。再確認しても送信済みでなければ、それは別原因（受信者 FK 違反等）の失敗なので ERROR。</p>
 *
 * <p><b>課金</b>: 同期 {@code sendFromSource} は自動・システム通知の経路であり F09.13 のカウント対象外
 * （組織の通知クレジット残高・猶予期限にかかわらず送る）。</p>
 *
 * <p><b>既知の制約</b>: 受信者が501人以上の募集は {@code send} の上限で {@code SEND_FAILED} となり送れない
 * （ERROR ログで可視化。別台帳行で扱う）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentAutoCancelledNotificationListener {

    static final String SOURCE_TYPE = "RECRUITMENT_AUTO_CANCEL";

    /** 確認期限（自動キャンセル通知の発行から72時間。旧バッチ内同期送信と同じ値）。 */
    private static final long DEADLINE_HOURS = 72L;

    private final ConfirmableNotificationService confirmableNotificationService;
    private final ConfirmableNotificationRepository confirmableNotificationRepository;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "確定した自動キャンセルを参加者へ知らせるため、募集機能のgate状態にかかわらず配送する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAutoCancelled(RecruitmentAutoCancelledNotificationEvent event) {
        if (event.listingId() == null || event.sourceScopeType() == null || event.sourceScopeId() == null
                || event.recipientUserIds() == null || event.recipientUserIds().isEmpty()) {
            log.warn("募集自動キャンセル通知をスキップ（イベントの必須項目欠落）: listingId={}, scopeType={}, scopeId={}",
                    event.listingId(), event.sourceScopeType(), event.sourceScopeId());
            return;
        }
        Long listingId = event.listingId();

        try {
            if (confirmableNotificationRepository.existsBySourceTypeAndSourceId(SOURCE_TYPE, listingId)) {
                log.info("募集自動キャンセル通知は送信済みのためスキップ: listingId={}", listingId);
                return;
            }
            confirmableNotificationService.sendFromSource(
                    SOURCE_TYPE,
                    listingId,
                    toNotificationScope(event.sourceScopeType()),
                    event.sourceScopeId(),
                    "募集が自動キャンセルされました",
                    "最小定員を達成できなかったため自動キャンセルされました",
                    ConfirmableNotificationPriority.URGENT,
                    LocalDateTime.now(UserZoneLocalDateTimeParser.SERVER_ZONE).plusHours(DEADLINE_HOURS),
                    "/notifications",
                    SystemUsers.SYSTEM_USER_ID,
                    event.recipientUserIds());
        } catch (DataIntegrityViolationException e) {
            if (alreadySent(listingId)) {
                // 並行発火の後着側: uq_cn_once_per_source が INSERT を拒否した。先着側が送信済みなので重複として扱う。
                log.info("募集自動キャンセル通知は並行発火の先着側が送信済み（一意制約で重複を抑止）: listingId={}", listingId);
                return;
            }
            log.error("募集自動キャンセル通知の配送失敗: listingId={}, recipientCount={}",
                    listingId, event.recipientUserIds().size(), e);
        } catch (RuntimeException e) {
            log.error("募集自動キャンセル通知の配送失敗: listingId={}, recipientCount={}",
                    listingId, event.recipientUserIds().size(), e);
        }
    }

    /** 一意制約違反の後、送信済みかを再確認する。再確認自体が失敗した場合は「送信済みでない」として ERROR 側へ倒す。 */
    private boolean alreadySent(Long listingId) {
        try {
            return confirmableNotificationRepository.existsBySourceTypeAndSourceId(SOURCE_TYPE, listingId);
        } catch (RuntimeException recheckFailure) {
            log.warn("募集自動キャンセル通知の送信済み再確認に失敗: listingId={}", listingId, recheckFailure);
            return false;
        }
    }

    /** 募集スコープ → 確認通知スコープ。PERSONAL は PLATFORM に写像する（旧バッチ内同期送信と同じ写像）。 */
    private static ScopeType toNotificationScope(RecruitmentScopeType scopeType) {
        return scopeType == RecruitmentScopeType.PERSONAL
                ? ScopeType.PLATFORM
                : ScopeType.valueOf(scopeType.name());
    }
}
