package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.repository.AdCampaignDeliveryClaimRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.id.uuid.CustomVersionOneStrategy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F09.17 Phase 11-c キャンペーン配信 claim-then-act サービス。
 *
 * <p>{@code ad_campaign_delivery_claims} の {@code (campaign_id, user_id, week_start)} 一意制約を
 * 「先に場所を取ってから配る」の根拠とする。{@code BlogMediaService#cleanupOrphanMedia} が
 * 条件付き DELETE の影響行数で「行を確保できたか」を判定する作法に倣い、本サービスは
 * {@link AdCampaignDeliveryClaimRepository#tryClaim}（{@code INSERT IGNORE}）の影響行数で
 * 「claim を確保できたか」を判定する（例外は使わない）。</p>
 *
 * <h3>なぜ例外捕捉（save + DataIntegrityViolationException）ではなく INSERT IGNORE か</h3>
 * <p>当初は JPA の {@code save} を {@code REQUIRES_NEW} の中で行い一意制約違反を
 * {@code DataIntegrityViolationException} として捕まえる方式だったが、これは原理的に成立しない。
 * 一意制約違反が起きた時点で Spring はそのトランザクションを rollback-only に印付けるため、
 * 例外を捕まえて正常 return しても、トランザクション終了時のコミットで
 * {@code UnexpectedRollbackException} が飛ぶ（Spring の仕様どおりの挙動。CIの実 DB 結合テストで
 * 実際に再現した）。{@code INSERT IGNORE} は制約違反そのものを例外化しないため、
 * トランザクションが rollback-only化されず、この破綻が原理的に起こらない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdCampaignDeliveryClaimService {

    private final AdCampaignDeliveryClaimRepository claimRepository;

    /**
     * (campaignId, userId, weekStart) の claim を確保しようと試みる。
     *
     * <p>{@code REQUIRES_NEW} は維持するが、これはもはや「一意制約違反の巻き込み防止」のためではない
     * （{@code INSERT IGNORE} は例外を投げないため、そもそも呼び出し元トランザクションを
     * rollback-only にする経路が無い）。REQUIRES_NEW を維持する理由は、claim の確保・解放を
     * user 単位の配信処理と独立したコミット単位に保ち、後続の配信処理が例外で巻き戻っても
     * claim/解放の事実が失われないようにするため。</p>
     *
     * <p>id は JPA の {@code @GeneratedValue} ライフサイクルを経由しないネイティブ INSERT のため
     * 呼び出し側で事前生成する。{@link com.mannschaft.app.common.entity.UuidV7Entity} と同じ
     * 採番機構（Hibernate {@code @UuidGenerator(style = TIME)} が内部で使う
     * {@link CustomVersionOneStrategy}）をそのまま呼び出すことで、B-Tree page split 抑制という
     * UUIDv7 採用の目的をこのテーブルでも維持する。</p>
     *
     * @return 確保できた場合 true。既に他の実行が確保済みの場合 false（例外は投げない）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(UUID campaignId, Long userId, LocalDate weekStart) {
        if (campaignId == null || userId == null || weekStart == null) {
            throw new IllegalArgumentException("campaignId, userId, weekStart は必須です");
        }
        UUID id = UUID_GENERATOR.generateUuid(null);
        int inserted = claimRepository.tryClaim(id, campaignId, userId, weekStart);
        if (inserted == 0) {
            log.debug("AD_DELIVERY_CLAIM_CONFLICT campaignId={} userId={} weekStart={}",
                    campaignId, userId, weekStart);
            return false;
        }
        return true;
    }

    /**
     * {@link com.mannschaft.app.common.entity.UuidV7Entity} が {@code @UuidGenerator(style = TIME)}
     * 経由で使うのと同じ採番戦略。{@code generateUuid} は引数の session を参照しないため
     * {@code null} を渡してよい（Hibernate 実装依存だが、当面の Hibernate バージョンで確認済み）。
     */
    private static final CustomVersionOneStrategy UUID_GENERATOR = new CustomVersionOneStrategy();

    /**
     * 全チャネル skip（実配信 0 件）だった場合に claim を解放する。
     * FreqCap の {@link AdFrequencyCapService#releaseSlot} とセットで呼ぶこと。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseClaim(UUID campaignId, Long userId, LocalDate weekStart) {
        long deleted = claimRepository.deleteByCampaignIdAndUserIdAndWeekStart(campaignId, userId, weekStart);
        log.debug("AD_DELIVERY_CLAIM_RELEASED campaignId={} userId={} weekStart={} deleted={}",
                campaignId, userId, weekStart, deleted);
    }

    /**
     * 候補ユーザー一覧から既 claim 済みユーザーを除外するための集合を返す。
     *
     * <p>週開始はユーザー TZ 依存で単一の値に定まらないため、{@code today} 基準で
     * ±8 日程度の広めの範囲（{@link #CLAIM_LOOKUP_MARGIN_DAYS}）を対象にする。
     * 過剰除外は「次回実行まで再試行が遅れる」だけで、狭すぎる範囲による取りこぼしと違い
     * 二重配信を起こさないため安全側に倒す。</p>
     */
    @Transactional(readOnly = true)
    public Set<Long> findClaimedUserIds(UUID campaignId, LocalDate today) {
        LocalDate rangeStart = today.minusDays(CLAIM_LOOKUP_MARGIN_DAYS);
        LocalDate rangeEnd = today.plusDays(1);
        return new HashSet<>(claimRepository.findClaimedUserIds(campaignId, rangeStart, rangeEnd));
    }

    /** 週境界がユーザー TZ 依存であることを踏まえた、claim 検索の安全マージン日数。 */
    static final int CLAIM_LOOKUP_MARGIN_DAYS = 8;
}
