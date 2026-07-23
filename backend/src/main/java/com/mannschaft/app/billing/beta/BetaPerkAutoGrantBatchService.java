package com.mannschaft.app.billing.beta;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F20.3 Phase2 Wave2a: 活動実績ゲートによる<b>個人特典（INDIVIDUAL）の自動付与バッチ</b>（設計書 F20.3 03 §6）。
 *
 * <p>現行フェーズ（{@code mannschaft.beta.current-phase}）の {@code beta_perk_criteria}（INDIVIDUAL）を満たす
 * 活性ユーザーへ、{@link BetaGrantService#grantBetaPerk} を {@code grantedBy=null}（SYSTEM）で付与する。
 * TEAM_ORG は候補 dry-run → シスアド手動付与（{@code BetaPerkCandidateService}）の責務ゆえ本バッチの対象外。</p>
 *
 * <h3>本番無効化（運用フラグ）</h3>
 * <p>{@code mannschaft.beta.auto-grant.enabled}（既定 {@code false}）が唯一の起動ゲート。false の間は
 * {@link #execute()} は即 no-op（付与 0・AC-N3）。本番はマスター御裁可を経るまで false 不変。</p>
 *
 * <h3>N+1 回避（AC-P1/P2・本バッチ設計の核心）</h3>
 * <p>付与判定は <b>{@code BetaPerkEligibilityService#evaluate}（@Cacheable "betaPerk:eligibility"）を per-user で
 * 呼ばない</b>。1 ページ（最大 {@link #PAGE_SIZE} 件）につき「活性ユーザーID列挙 1・付与済み skip-set 1・activeDays
 * bulk 1・在籍日数 bulk 1」の<b>ページ内定数本クエリ</b>で先読みし、判定はメモリ内で行う。付与呼び出しには
 * {@code skipCriteriaCheck=true} を渡し、{@code grantBetaPerk} 内の per-user 再評価（@Cacheable 汚染・N+1）を回避する。
 * 本クラスは {@code BetaPerkEligibilityService} に依存しない（構造的に evaluate() を呼ばない）。</p>
 *
 * <h3>冪等・耐障害（AC-I2/I5・途中失敗継続）</h3>
 * <ul>
 *   <li>skip-set（{@code uk_bg_scope_phase} と同一軸・取消済み含む）で先に二重付与を弾く。取りこぼしても
 *       {@code grantBetaPerk} の二重付与検出（{@link BetaPerkErrorCode#GRANT_ALREADY_EXISTS}）と
 *       {@link DataIntegrityViolationException}（並行 uk 競合）を per-item で捕捉し冪等 skip する。</li>
 *   <li>{@link #execute()} は <b>非 {@code @Transactional}</b>（クロスドメイン tx 番人 D-3 回避＋全件 all-or-nothing 化の
 *       回避）。各付与は {@code grantBetaPerk} 自身の {@code @Transactional} で原子性を担保するため、途中の 1 件が
 *       失敗しても既にコミット済みの付与は残り、残りのユーザーの付与は継続する（症状を握り潰さず WARN で可視化）。</li>
 * </ul>
 *
 * <h3>クロスドメイン境界（D-1/D-3）</h3>
 * <p>本バッチは billing ドメインだが、活性ユーザー列挙のため auth の {@link UserRepository} を参照する。ただし
 * <b>scalar（{@code Long} ID）射影のみ</b>を扱い他ドメイン Entity を import しない（D-1 回避・{@code LoginActivity
 * QueryService} が {@code AuditLogRepository} を scalar 参照するのと同型）。activeDays / 在籍日数は billing.beta の
 * {@link LoginActivityQueryService} / {@link MembershipQueryService} 経由で解決する。{@link #execute()} は非
 * {@code @Transactional} ゆえ D-3 にも抵触しない。</p>
 *
 * <h3>スケジュール・多重起動防止・テスト</h3>
 * <p>毎日 04:00 JST。{@code @SchedulerLock} で多重ノード起動を防ぎ、{@link Clock} 注入で評価ウィンドウ・在籍日数の
 * 境界（AC-B1）を決定論的に検証できる。金型: {@code GuardianshipProgressionNoticeBatchService}。</p>
 *
 * <h3>タイムゾーン境界（要監視・殿がマスターに諮る論点）</h3>
 * <p>activeDays は {@code audit_logs} の {@code COUNT(DISTINCT DATE(created_at))}（{@code AuditLogRepository}）で数え、
 * {@code DATE()} の日境界は<b>DB セッションの time_zone</b>に従う。本バッチは cron を {@code Asia/Tokyo}（JST 日次）で
 * 回す設計正準を前提とし、本 Wave では {@code CONVERT_TZ} 正準化を導入していない（本番 DB セッション tz が JST でない
 * 場合、活性日の日境界が UTC でカウントされ JST 深夜帯のログインが前日/翌日に寄る可能性がある）。{@code CONVERT_TZ}
 * 正準化の要否は殿がマスターに諮る論点として本コメントに残す（勝手に入れも外しもせず、判断根拠を明示）。</p>
 *
 * <p>設計書: docs/features/F20.3_beta_perks/03_security.md §6 / 02_api_design.md §3</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BetaPerkAutoGrantBatchService {

    /** 1 ページあたりの活性ユーザー取得件数（bulk 判定の単位）。 */
    static final int PAGE_SIZE = 500;

    /** 全件走査の暴走を防ぐ最大ページ数（500 件 × 200 ページ = 10 万ユーザー／回）。 */
    static final int MAX_PAGES = 200;

    private final BetaPerkCriteriaRepository criteriaRepository;
    private final LoginActivityQueryService loginActivityQueryService;
    private final MembershipQueryService membershipQueryService;
    private final BetaGrantRepository betaGrantRepository;
    private final UserRepository userRepository;
    private final BetaGrantService betaGrantService;
    private final Clock clock;

    /** 自動付与バッチの有効化フラグ（既定 false = no-op・設計書 03 §6）。 */
    @Value("${mannschaft.beta.auto-grant.enabled:false}")
    private boolean autoGrantEnabled;

    /** 対象ベータ段階（{@code BetaGrantQueryService} と同一プロパティ）。 */
    @Value("${mannschaft.beta.current-phase:1}")
    private int currentPhase;

    /**
     * 個人特典の自動付与バッチ。毎日 04:00 JST に実行する。
     */
    @BatchEndpoint(name = "beta-perk-auto-grant-batch",
            description = "F20.3 個人ベータ特典 活動実績ゲート自動付与バッチ（INDIVIDUAL のみ）")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "betaPerkAutoGrant", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void execute() {
        // 起動ゲート（AC-N3）: 無効なら一切走査せず即 no-op。本番は御裁可まで false 不変。
        if (!autoGrantEnabled) {
            log.debug("ベータ特典 自動付与バッチ: 無効（mannschaft.beta.auto-grant.enabled=false）ゆえ no-op");
            return;
        }

        long startedAt = System.currentTimeMillis();
        int phase = currentPhase;
        log.info("ベータ特典 自動付与バッチ開始: phase={}", phase);

        // 現行フェーズの INDIVIDUAL criteria（未定義 / enabled=false は付与 0 で正常終了・例外で全体を止めない）。
        BetaPerkCriteriaEntity criteria = criteriaRepository
                .findById(new BetaPerkCriteriaId(phase, GrantKind.INDIVIDUAL))
                .filter(BetaPerkCriteriaEntity::isEnabled)
                .orElse(null);
        if (criteria == null) {
            log.info("ベータ特典 自動付与バッチ完了: phase={} の INDIVIDUAL criteria が未定義/無効のため付与0", phase);
            return;
        }

        Integer minActiveDays = criteria.getMinActiveDays();
        Integer minTenureDays = criteria.getMinMembershipTenureDays();
        // 主原則（無活動ユーザーへのバラ撒き防止）: 個人特典に適用可能な指標（activeDays / 在籍日数）が両方 NULL の
        // criteria は「無条件付与」に相当するため、自動付与では 1 件も付与しない（min_active_members は TEAM_ORG 専用）。
        if (minActiveDays == null && minTenureDays == null) {
            log.warn("ベータ特典 自動付与バッチ完了: phase={} の INDIVIDUAL criteria が全指標NULL（無条件付与防止）ゆえ付与0",
                    phase);
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(criteria.getEvaluationWindowDays());

        int granted = 0;
        int skipped = 0;
        int failed = 0;

        for (int page = 0; page < MAX_PAGES; page++) {
            Page<Long> userPage = userRepository.findActiveUserIdsForBeta(
                    PageRequest.of(page, PAGE_SIZE, Sort.by("id").ascending()));
            List<Long> userIds = userPage.getContent();
            if (userIds.isEmpty()) {
                break;
            }

            // ページ内定数本クエリの bulk 先読み（AC-P1/P2）。
            Set<Long> alreadyGranted = new HashSet<>(
                    betaGrantRepository.findGrantedScopeIds(phase, EntitlementScopeKind.USER, userIds));
            Map<Long, Long> activeDaysByUser = minActiveDays != null
                    ? loginActivityQueryService.countDistinctActiveDaysByUsers(userIds, since)
                    : Collections.<Long, Long>emptyMap();
            Map<Long, Long> tenureDaysByUser = minTenureDays != null
                    ? membershipQueryService.tenureDaysByUsers(userIds, now)
                    : Collections.<Long, Long>emptyMap();

            for (Long userId : userIds) {
                // 付与済み（取消済み含む＝再付与不可）は skip。
                if (alreadyGranted.contains(userId)) {
                    skipped++;
                    continue;
                }
                // メモリ内判定: 非 NULL 指標を AND・境界は「以上」（actual >= required）。記録欠損は 0 扱い。
                if (!isEligible(userId, minActiveDays, minTenureDays, activeDaysByUser, tenureDaysByUser)) {
                    skipped++;
                    continue;
                }
                // 付与（バッチが既に bulk 判定済ゆえ skipCriteriaCheck=true で per-user 再評価を回避・grantedBy=null=SYSTEM）。
                GrantOutcome outcome = grantOne(userId, phase);
                if (outcome == GrantOutcome.GRANTED) {
                    granted++;
                } else if (outcome == GrantOutcome.SKIPPED) {
                    skipped++;
                } else {
                    failed++;
                }
            }

            if (!userPage.hasNext()) {
                break;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startedAt;
        log.info("ベータ特典 自動付与バッチ完了: phase={}, 付与={}件, スキップ={}件, 失敗={}件, 所要={}ms",
                phase, granted, skipped, failed, elapsedMs);
    }

    /** メモリ内の充足判定（非 NULL 指標を AND・境界は「以上」・記録欠損は 0 日扱い）。 */
    private boolean isEligible(Long userId, Integer minActiveDays, Integer minTenureDays,
                               Map<Long, Long> activeDaysByUser, Map<Long, Long> tenureDaysByUser) {
        if (minActiveDays != null && activeDaysByUser.getOrDefault(userId, 0L) < minActiveDays) {
            return false;
        }
        return minTenureDays == null || tenureDaysByUser.getOrDefault(userId, 0L) >= minTenureDays;
    }

    /** 1 件の付与を試み、結果を返す（冪等 skip / 失敗継続を per-item で吸収する）。 */
    private GrantOutcome grantOne(Long userId, int phase) {
        try {
            betaGrantService.grantBetaPerk(
                    GrantKind.INDIVIDUAL, phase, EntitlementScopeKind.USER, userId,
                    null, true, null);
            return GrantOutcome.GRANTED;
        } catch (BusinessException be) {
            if (be.getErrorCode() == BetaPerkErrorCode.GRANT_ALREADY_EXISTS) {
                // 既付与（skip-set 取りこぼし or 並行付与）。冪等に skip。
                return GrantOutcome.SKIPPED;
            }
            // 想定外の業務例外は症状を握り潰さず WARN で可視化し、当該ユーザーのみ失敗として継続。
            log.warn("ベータ特典 自動付与に失敗（継続）userId={}, errorCode={}",
                    userId, be.getErrorCode(), be);
            return GrantOutcome.FAILED;
        } catch (DataIntegrityViolationException dive) {
            // uk_bg_scope_phase の並行競合（grantBetaPerk は変換しないため本バッチ側で捕捉）。冪等に skip。
            log.debug("ベータ特典 自動付与: uk_bg_scope_phase 並行競合のため skip userId={}", userId);
            return GrantOutcome.SKIPPED;
        } catch (RuntimeException ex) {
            // その他の実行時例外も 1 件失敗で全体を止めず、WARN で可視化して継続。
            log.warn("ベータ特典 自動付与に失敗（継続）userId={}", userId, ex);
            return GrantOutcome.FAILED;
        }
    }

    /** 1 件付与の結末（集計用）。 */
    private enum GrantOutcome {
        GRANTED, SKIPPED, FAILED
    }
}
