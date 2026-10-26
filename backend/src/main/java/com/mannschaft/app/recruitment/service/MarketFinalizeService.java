package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationStatus;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

/**
 * F22.1 市「札を下げる」最終認証（02_api_design §6.1 / 01_data_model §5）。
 *
 * <p>札が要件充足（{@code FULL}）したとき、札主 scope の権限者へ F04.9 確認通知
 * （{@code source_type='MARKET_FINALIZE'}, {@code source_id=listingId}）を送る。送信は申込の業務TXの外で
 * {@link MarketFinalizeConfirmationListener}（AFTER_COMMIT + {@code @Async}）が行い、本サービスは送信内容の
 * 決定（{@link #planFinalizeConfirmation(Long)}）だけを担う（CMP-260930-1932）。確認応答を
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
    private final ConfirmableNotificationRepository confirmableNotificationRepository;
    private final UserRoleRepository userRoleRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 最終認証の確認通知の送り先・文面（{@link #planFinalizeConfirmation(Long)} の結果）。
     *
     * @param listingId        札ID（{@code source_id}）
     * @param scopeType        確認通知のスコープ（PERSONAL 札は PLATFORM）
     * @param scopeId          スコープID
     * @param title            タイトル
     * @param body             本文
     * @param actionUrl        遷移先
     * @param createdByUserId  作成者（札主）
     * @param recipientUserIds 受信者（札主 scope の ADMIN。不在なら札主本人）
     */
    public record FinalizeConfirmationPlan(
            Long listingId,
            ScopeType scopeType,
            Long scopeId,
            String title,
            String body,
            String actionUrl,
            Long createdByUserId,
            List<Long> recipientUserIds) {
    }

    /**
     * 札が {@code FULL} に到達したとき、札主へ送る最終認証の確認通知の内容を決める（送信はしない）。
     *
     * <p>CMP-260930-1932: 送信は申込の業務TXの外（{@link MarketFinalizeConfirmationListener}、
     * AFTER_COMMIT + {@code @Async}）で行う。本メソッドは札の<b>最新状態を読み直し</b>、{@code FULL} で
     * なければ（同一TX内で OPEN に戻った・既に COMPLETED 等）送らない判断を返す。</p>
     *
     * <p>重複発火ガード（02_api_design §6.1）: FULL→OPEN→再FULL で確認通知が二重送信されるのを防ぐ。
     * 同一札（source_id）に未確認（ACTIVE）の MARKET_FINALIZE 通知が既に存在すれば送らない。</p>
     *
     * <p><b>札の行ロック下で判定する（Codex 検分 P2）</b>: 札行を {@code PESSIMISTIC_WRITE} で取り、
     * そのロックを<b>呼び出し側の通知TXのコミットまで握ったまま</b>状態と既存 ACTIVE 通知を確認する。
     * 札の status を変える経路（申込 {@code apply}・本人キャンセル {@code cancelMyApplication}・
     * 管理者キャンセル・自動下げバッチ・{@link #finalizeBySourceId}）はいずれも同じ札行を
     * {@code findByIdForUpdate} で取るか native UPDATE（＝同じ行の排他ロック）で書き換えるため、
     * ①「FULL を確認した後・通知を作る前に参加者キャンセルで OPEN に戻る」ことは起きず、
     * ② FULL→OPEN→再FULL の2イベントの判定も直列化され、後続は先行の ACTIVE 通知を必ず見る。
     * したがって呼び出し側は<b>この呼び出しを通知TXの最初のクエリにし</b>、同じTX内で通知を作ること
     * （InnoDB の REPEATABLE READ は最初の一貫性読み取りで読取ビューを作るため、ロック取得後に作られる
     * ビューが先行TXのコミット済み通知を含む）。{@code MANDATORY}: TX 外から呼ぶとロックが即座に外れ
     * 直列化が成立しないため、呼び出し側のTXが無ければ {@link IllegalStateException} にする。</p>
     *
     * <p>{@code @Transactional(propagation = MANDATORY)} を付けず実行時に検査する理由: 宣言すると本メソッドが
     * D-3T 番人の {@code @Transactional} 入口になり、通知・ロールの Repository へ届く新規の越境TX入口を
     * 増やす。TX を開く責務は通知TXを束ねる {@link MarketFinalizeConfirmationListener} 側にあり、
     * 越境の理由もそこに明記している。</p>
     *
     * @param listingId 札ID
     * @return 送るべきなら送信内容、送らないなら空
     * @throws IllegalStateException 実TXの外から呼ばれた場合
     */
    public Optional<FinalizeConfirmationPlan> planFinalizeConfirmation(Long listingId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "planFinalizeConfirmation は札の行ロックを通知作成まで握るため、通知TXの内側で呼ぶこと: listingId="
                            + listingId);
        }
        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId).orElse(null);
        if (listing == null) {
            log.warn("F22.1 市: 最終認証通知の対象札が不在（削除済み等）: listingId={}", listingId);
            return Optional.empty();
        }
        if (listing.getStatus() != RecruitmentListingStatus.FULL) {
            log.info("F22.1 市: 最終認証通知スキップ（コミット時点で FULL ではない）: listingId={}, status={}",
                    listingId, listing.getStatus());
            return Optional.empty();
        }

        boolean alreadyPending = confirmableNotificationRepository
                .existsBySourceTypeAndSourceIdAndStatus(
                        SOURCE_TYPE_MARKET_FINALIZE, listing.getId(),
                        ConfirmableNotificationStatus.ACTIVE);
        if (alreadyPending) {
            log.info("F22.1 市: 最終認証の確認通知は既に未確認で存在するため再送スキップ: listingId={}",
                    listing.getId());
            return Optional.empty();
        }

        ScopeType scopeType = switch (listing.getScopeType()) {
            // 個人札には membership の組織スコープが無いため、明示受信者付き PLATFORM を使う。
            case PERSONAL -> ScopeType.PLATFORM;
            case TEAM -> ScopeType.TEAM;
            case ORGANIZATION -> ScopeType.ORGANIZATION;
            case GLOBAL -> throw new IllegalArgumentException("募集枠に GLOBAL スコープは使用できません");
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

        return Optional.of(new FinalizeConfirmationPlan(
                listing.getId(),
                scopeType,
                listing.getScopeId(),
                "募集を確定して札を下げますか？",
                listing.getTitle() + " が定員に達しました。最終認証で募集を確定できます。",
                "/market/listings/" + listing.getId(),
                listing.getCreatedBy(),
                recipientUserIds));
    }

    /**
     * 最終認証の確認通知の優先度（02_api_design §6.1: 札主の確定操作を促すため HIGH）。
     *
     * <p>送信内容の record（{@link FinalizeConfirmationPlan}）に通知ドメインの型を持たせない
     * （D-1: 他ドメインの entity パッケージへの依存を recruitment の新しいクラスへ広げない）。</p>
     *
     * @return 優先度
     */
    public ConfirmableNotificationPriority finalizeConfirmationPriority() {
        return ConfirmableNotificationPriority.HIGH;
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
