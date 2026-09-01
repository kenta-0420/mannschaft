package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.event.MarketListingFinalizedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F22.1 市「札を下げる」最終認証（02_api_design §6.1 / 01_data_model §5）。
 *
 * <p>札が要件充足（{@code FULL}）したとき、札主 scope の権限者へ F04.9 確認通知
 * （{@code source_type='MARKET_FINALIZE'}, {@code source_id=listingId}）を送る。確認応答を
 * 受けて {@link MarketFinalizeConfirmedListener} が {@link #finalizeBySourceId(Long)} を呼び、
 * 札行を {@code PESSIMISTIC_WRITE} でロックして {@code FULL→COMPLETED} に遷移させる。</p>
 *
 * <p><strong>乖離A の根治（第二陣）</strong>: 「source_type 拡張のみで済む」は誤りで、本連携は
 * 新規実装である。{@code send()} オーバーロード（{@code sendFromSource}）と確認後リスナを新設した。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketFinalizeService {

    /** 市の最終認証で用いる発生元種別。 */
    public static final String SOURCE_TYPE_MARKET_FINALIZE = "MARKET_FINALIZE";

    private final RecruitmentListingRepository listingRepository;
    private final ConfirmableNotificationService confirmableNotificationService;
    private final ConfirmableNotificationRepository confirmableNotificationRepository;
    private final UserRoleRepository userRoleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 札が {@code FULL} に到達したとき、札主へ最終認証の確認通知を送る。
     *
     * <p>{@code RecruitmentParticipantService} の申込確定フローから、原子的 INSERT 後の
     * 再ロードで {@code FULL} を検知したときに呼び出す。冪等性は呼び出し側の状態判定に委ねる。</p>
     *
     * @param listing FULL 状態の札（再ロード済み）
     */
    @Transactional
    public void sendFinalizeConfirmation(RecruitmentListingEntity listing) {
        if (listing.getStatus() != RecruitmentListingStatus.FULL) {
            return;
        }

        // 重複発火ガード（02_api_design §6.1）: FULL→OPEN→再FULL で確認通知が二重送信されるのを防ぐ。
        // 同一札（source_id）に未確認（ACTIVE）の MARKET_FINALIZE 通知が既に存在すれば再送しない。
        boolean alreadyPending = confirmableNotificationRepository
                .existsBySourceTypeAndSourceIdAndStatus(
                        SOURCE_TYPE_MARKET_FINALIZE, listing.getId(),
                        ConfirmableNotificationStatus.ACTIVE);
        if (alreadyPending) {
            log.info("F22.1 市: 最終認証の確認通知は既に未確認で存在するため再送スキップ: listingId={}",
                    listing.getId());
            return;
        }

        ScopeType scopeType = switch (listing.getScopeType()) {
            // 個人札には membership の組織スコープが無いため、明示受信者付き PLATFORM を使う。
            case PERSONAL -> ScopeType.PLATFORM;
            case TEAM -> ScopeType.TEAM;
            case ORGANIZATION -> ScopeType.ORGANIZATION;
        };

        // 札主 scope の ADMIN を受信者にする。ADMIN 不在なら作成者本人にフォールバック。
        List<Long> recipientUserIds = switch (scopeType) {
            case PLATFORM -> List.of(listing.getCreatedBy());
            case TEAM -> userRoleRepository.findUserIdsByTeamIdAndRoleName(listing.getScopeId(), "ADMIN");
            case ORGANIZATION -> userRoleRepository.findUserIdsByScope("ORGANIZATION", listing.getScopeId());
            default -> List.of();
        };
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            recipientUserIds = List.of(listing.getCreatedBy());
        }

        confirmableNotificationService.sendFromSource(
                SOURCE_TYPE_MARKET_FINALIZE,
                listing.getId(),
                scopeType,
                listing.getScopeId(),
                "募集を確定して札を下げますか？",
                listing.getTitle() + " が定員に達しました。最終認証で募集を確定できます。",
                ConfirmableNotificationPriority.HIGH,
                null,
                "/market/listings/" + listing.getId(),
                listing.getCreatedBy(),
                recipientUserIds);

        log.info("F22.1 市: 最終認証の確認通知を送信: listingId={}, recipients={}",
                listing.getId(), recipientUserIds.size());
    }

    /**
     * 最終認証の確認応答を受けて札を {@code FULL→COMPLETED} に遷移させる。
     *
     * <p>札行を {@code PESSIMISTIC_WRITE} でロックして直列化する（自動下げバッチ・2 人目 confirm との
     * 競合回避。02_api_design §6.1）。既に {@code FULL} 以外（＝先勝ちで COMPLETED 済み・キャンセル済み等）
     * なら冪等に no-op する。</p>
     *
     * <p>確認後リスナ（AFTER_COMMIT + @Async）から呼ばれるため、新規トランザクションで実行する。</p>
     *
     * @param listingId 札ID（{@code source_id}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeBySourceId(Long listingId) {
        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId).orElse(null);
        if (listing == null) {
            log.warn("F22.1 市: 最終認証対象の札が不在（削除済み等）: listingId={}", listingId);
            return;
        }
        if (listing.getStatus() != RecruitmentListingStatus.FULL) {
            // 先勝ち COMPLETED / バッチによる AUTO_CANCELLED 等 → 冪等 no-op。
            log.info("F22.1 市: 最終認証 no-op（FULL 以外）: listingId={}, status={}",
                    listingId, listing.getStatus());
            return;
        }
        listing.finalizeComplete();
        listingRepository.save(listing);
        log.info("F22.1 市: 最終認証完了 FULL→COMPLETED: listingId={}", listingId);

        // 謝礼の払出（capture+transfer）を起こす（02 §5.3）。札行 PESSIMISTIC_WRITE ロック直下・同一
        // トランザクション内で同期発火し、payment.escrow が購読して capture する（疎結合・クロスドメイン FK なし）。
        // 謝礼なし（payment_enabled=false）札は payment 側で no-op になる。
        eventPublisher.publishEvent(new MarketListingFinalizedEvent(
                listing.getId(), Boolean.TRUE.equals(listing.getPaymentEnabled())));
    }
}
