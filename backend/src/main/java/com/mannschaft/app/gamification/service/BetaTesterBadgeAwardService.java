package com.mannschaft.app.gamification.service;

import com.mannschaft.app.gamification.AwardedBy;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * F20.3 ベータ特典: ベータテスター称号バッジの<b>SYSTEM 授与</b>（設計書 F20.3 01 §5 / README §4）。
 *
 * <p><b>なぜ専用サービスか</b>:</p>
 * <ul>
 *   <li>{@code GamificationBadgeService.awardBadgeManually} は ADMIN 固定・scope 一致ガード付きの
 *       手動付与導線であり、SYSTEM 経路（sentinel PLATFORM スコープの system badge）には流用できない。</li>
 *   <li>本サービスを<b>gamification ドメインに置く</b>ことで、billing.beta（{@link
 *       com.mannschaft.app.billing.beta.BetaGrantService}）は gamification の Entity/Repository を直接
 *       参照せず<b>本サービス（Service 経由）</b>を呼ぶだけで済む（CLAUDE.md「ドメイン間のデータ取得は
 *       Service のメソッド呼び出し経由」・クロスドメイン Entity 参照番人 D-1 / Repository 参照番人 D-3 を回避）。</li>
 * </ul>
 *
 * <p><b>非致命・冪等・独立 tx</b>: バッジ授与の失敗は特典付与本体をロールバックしない（AC-I3・設計書 01 §5）。
 * そのため {@code REQUIRES_NEW} で独立トランザクションとして実行し、呼び出し元の grant トランザクションが
 * 授与失敗（例: uq 競合による {@link DataIntegrityViolationException}）で巻き込まれて rollback-only に
 * ならないようにする。バッジ行が未シードの場合は WARN ログを残して no-op（握りつぶさず可視化・症状を隠さない）。
 * フェーズ別の二重授与は {@code uq_ub_badge_user_period}（DB）で物理防止する（AC-11）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BetaTesterBadgeAwardService {

    /** ベータテスター称号バッジのシード識別（{@code V162...seed_beta_tester_badge.sql}・設計書 01 §5）。 */
    private static final String BETA_BADGE_SCOPE_TYPE = "PLATFORM";
    private static final Long BETA_BADGE_SCOPE_ID = 0L;
    private static final String BETA_BADGE_NAME = "ベータテスター";
    /** フェーズ別称号の {@code user_badges.period_label}（VARCHAR(20)・例 {@code BETA_PHASE_4}）。 */
    private static final String PERIOD_LABEL_PREFIX = "BETA_PHASE_";

    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final Clock clock;

    /**
     * ベータテスター称号を SYSTEM 授与する（同フェーズは冪等）。
     *
     * @param userId    授与対象ユーザー
     * @param betaPhase ベータ段階（1〜4・{@code period_label} に反映）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void awardBetaTesterBadge(Long userId, int betaPhase) {
        if (userId == null) {
            return;
        }
        BadgeEntity badge = badgeRepository
                .findByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                        BETA_BADGE_SCOPE_TYPE, BETA_BADGE_SCOPE_ID, BETA_BADGE_NAME)
                .orElse(null);
        if (badge == null) {
            // シード未適用等。付与本体は殺さず可視化する（補助チャネルゆえ WARN で継続）。
            log.warn("ベータテスター称号バッジが未シードのため授与をスキップ（userId={}, betaPhase={}）",
                    userId, betaPhase);
            return;
        }
        String periodLabel = PERIOD_LABEL_PREFIX + betaPhase;
        // 冪等: 同フェーズ既授与ならスキップ（DB uq_ub_badge_user_period の手前で早期リターン）。
        if (userBadgeRepository.existsByBadgeIdAndUserIdAndPeriodLabel(badge.getId(), userId, periodLabel)) {
            return;
        }
        try {
            userBadgeRepository.save(UserBadgeEntity.builder()
                    .badgeId(badge.getId())
                    .userId(userId)
                    .earnedOn(LocalDate.now(clock))
                    .periodLabel(periodLabel)
                    .awardedBy(AwardedBy.SYSTEM)
                    .build());
        } catch (DataIntegrityViolationException ex) {
            // 並行授与の uq 競合（先勝ち）。既授与と等価ゆえ冪等スキップ（独立 tx なので本体に波及しない）。
            log.debug("ベータテスター称号の並行授与を冪等スキップ（userId={}, betaPhase={}）", userId, betaPhase);
        }
    }
}
