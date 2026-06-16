package com.mannschaft.app.tournament.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.publicview.service.ViewerContextBuilder;
import com.mannschaft.app.publicview.visibility.AnonymousLabels;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.PostAuthor;
import com.mannschaft.app.publicview.visibility.ScopeRef;
import com.mannschaft.app.publicview.visibility.ScopeSettings;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.tournament.RankingsRecalculationEvent;
import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.TournamentMapper;
import com.mannschaft.app.tournament.dto.IndividualRankingResponse;
import com.mannschaft.app.tournament.dto.RankingSummaryResponse;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentIndividualRankingEntity;
import com.mannschaft.app.tournament.entity.TournamentFixturePlayerStatEntity;
import com.mannschaft.app.tournament.entity.TournamentStatDefEntity;
import com.mannschaft.app.tournament.repository.TournamentIndividualRankingRepository;
import com.mannschaft.app.tournament.repository.TournamentFixturePlayerStatRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.repository.TournamentStatDefRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 個人ランキングの自動計算サービス。
 *
 * <p><b>集計源泉は fixture 配下の選手スタッツ（{@code tournament_match_player_stats}・05 §H.2.2）</b>:
 * 本サービスは {@link TournamentFixturePlayerStatEntity}（fixture×user×statKey の EAV）を集計して
 * 得点王等を算出する。これらは fixture（matches 正本の派生スナップショット側ドメイン）に属する選手スタッツ
 * であり、matches/match_events へクロスドメイン JOIN せず tournament 自ドメイン内で集計が完結するよう
 * 設計されている（CLAUDE.md 原則 1・{@code CrossDomainEntityImportArchTest}）。</p>
 *
 * <p><b>基本スタッツの正本は match_events（Phase 5b-2・05 §H.2.2）</b>: 得点/アシスト等の基本スタッツ
 * （{@code BasicStatKeys}・"goals"/"assists"）の<b>正本は match ドメインの {@code match_events}</b>
 * （GOAL/ASSIST）であり、試合完了時に {@code MatchScoreFixtureListener} が match 集計から
 * {@code tournament_match_player_stats} スナップショットへ同期する。本サービスはそのスナップショットを
 * 読むだけのため、<b>集計ロジック・値・並び順・同点処理はすべて不変</b>（源泉が match になったことのみが差分）。
 * 大会主催者が任意定義する大会固有の独自 statKey（H.6）はスナップショット同期の対象外で、手入力
 * （{@code FixtureService.updatePlayerStats}）の値が従来どおり tournament 側に残置され、本サービスが同様に集計する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingsCalculationService {

    private final TournamentStatDefRepository statDefRepository;
    private final TournamentFixturePlayerStatRepository playerStatRepository;
    private final TournamentIndividualRankingRepository rankingRepository;
    private final TournamentMapper mapper;

    // F08.7 順位UI 項目①: ランキング選手名を F19.1 本人可視性経由で解決するための依存。
    private final TournamentRepository tournamentRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final IdentityVisibilityResolver identityVisibilityResolver;
    private final ViewerContextBuilder viewerContextBuilder;

    /**
     * ランキング再計算イベントを受信する。
     *
     * <p><b>レース条件根治（05 §H.0 訂正・順位表と同根）</b>: 以前は {@code @Async @EventListener}
     * だったため発火元TX（{@link FixtureService#updatePlayerStats} の {@code @Transactional}）の
     * <b>コミット前</b>に別スレッドで実行され、未コミットの選手スタッツを読んでランキングが
     * 自動反映されなかった。{@link TransactionalEventListener}(AFTER_COMMIT) に切り替え、確定後に
     * 再計算する。{@code @Async} 併存でコミット後の非同期実行を維持する。
     * AFTER_COMMIT 後はアクティブTXが無いため {@code REQUIRES_NEW}（{@code REQUIRED} は起動時禁止）。</p>
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRankingsRecalculation(RankingsRecalculationEvent event) {
        recalculateAll(event.getTournamentId());
    }

    /**
     * 全ランキング項目を再計算する。
     */
    @Transactional
    public void recalculateAll(Long tournamentId) {
        log.info("個人ランキング再計算開始: tournamentId={}", tournamentId);

        List<TournamentStatDefEntity> rankingDefs =
                statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(tournamentId);

        for (TournamentStatDefEntity def : rankingDefs) {
            recalculateForStatKey(tournamentId, def);
        }

        log.info("個人ランキング再計算完了: tournamentId={}", tournamentId);
    }

    private void recalculateForStatKey(Long tournamentId, TournamentStatDefEntity def) {
        String statKey = def.getStatKey();
        List<TournamentFixturePlayerStatEntity> allStats =
                playerStatRepository.findByTournamentIdAndStatKey(tournamentId, statKey);

        // ユーザーごとに集計
        Map<Long, PlayerAggregation> aggregations = new HashMap<>();

        for (TournamentFixturePlayerStatEntity stat : allStats) {
            PlayerAggregation agg = aggregations.computeIfAbsent(stat.getUserId(),
                    uid -> new PlayerAggregation(uid, stat.getParticipantId()));
            agg.matchCount++;

            switch (def.getDataType()) {
                case INTEGER -> {
                    if (stat.getValueInt() != null) {
                        agg.intValues.add(stat.getValueInt());
                    }
                }
                case DECIMAL -> {
                    if (stat.getValueDecimal() != null) {
                        agg.decimalValues.add(stat.getValueDecimal());
                    }
                }
                case TIME -> {
                    // TIME集計は将来対応
                }
            }
        }

        // 集計値の算出
        List<PlayerAggregation> sorted = new ArrayList<>(aggregations.values());
        for (PlayerAggregation agg : sorted) {
            switch (def.getDataType()) {
                case INTEGER -> agg.totalInt = aggregate(agg.intValues, def.getAggregationType());
                case DECIMAL -> agg.totalDecimal = aggregateDecimal(agg.decimalValues, def.getAggregationType());
                default -> {}
            }
        }

        // ソート（降順）
        sorted.sort((a, b) -> {
            if (a.totalInt != null && b.totalInt != null) return Integer.compare(b.totalInt, a.totalInt);
            if (a.totalDecimal != null && b.totalDecimal != null) return b.totalDecimal.compareTo(a.totalDecimal);
            return 0;
        });

        // ランキングの保存
        rankingRepository.deleteByTournamentIdAndStatKey(tournamentId, statKey);
        for (int i = 0; i < sorted.size(); i++) {
            PlayerAggregation agg = sorted.get(i);
            rankingRepository.save(TournamentIndividualRankingEntity.builder()
                    .tournamentId(tournamentId)
                    .userId(agg.userId)
                    .participantId(agg.participantId)
                    .statKey(statKey)
                    .rank(i + 1)
                    .totalValueInt(agg.totalInt)
                    .totalValueDecimal(agg.totalDecimal)
                    .matchesPlayed(agg.matchCount)
                    .build());
        }
    }

    private Integer aggregate(List<Integer> values, StatAggregationType type) {
        if (values.isEmpty()) return 0;
        return switch (type) {
            case SUM -> values.stream().mapToInt(Integer::intValue).sum();
            case AVG -> values.stream().mapToInt(Integer::intValue).sum() / values.size();
            case MAX -> values.stream().mapToInt(Integer::intValue).max().orElse(0);
            case MIN -> values.stream().mapToInt(Integer::intValue).min().orElse(0);
        };
    }

    private BigDecimal aggregateDecimal(List<BigDecimal> values, StatAggregationType type) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        return switch (type) {
            case SUM -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            case AVG -> values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
            case MAX -> values.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
            case MIN -> values.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        };
    }

    // ===== Query =====

    /**
     * 個人ランキングを取得する（F08.7 順位UI 項目①: 選手名を F19.1 本人可視性経由で解決）。
     *
     * <p>各ランキング行の {@code userId} を {@link UserRepository#findByIdIn(Collection)} で一括取得し
     * （N+1 回避）、{@link IdentityVisibilityResolver#resolveIdentityForViewer} を選手ごとに適用して
     * 表示名・匿名化フラグ・アバターを解決する。MINOR 保護・退会済み・本名/サポーター開示規約は
     * すべて Resolver に委ねる（無条件 displayName 露出はしない）。</p>
     *
     * <p>スコープは大会の主催 ORG（{@link TournamentEntity#getOrganizationId()}）。閲覧者立場は
     * {@link ViewerContextBuilder#buildForOrganization(Long, Long)} で当該 ORG に対し解決する。</p>
     *
     * @param tournamentId 大会 ID
     * @param statKey      成績項目キー
     * @param pageable     ページネーション
     * @param viewerUserId 閲覧者 user_id（未ログインなら {@code null}）。Controller で
     *                     {@code SecurityUtils.getCurrentUserIdOrNull()} から伝播される
     */
    public Page<IndividualRankingResponse> getRankings(Long tournamentId, String statKey, Pageable pageable,
                                                       Long viewerUserId) {
        TournamentStatDefEntity def = statDefRepository.findByTournamentIdAndStatKey(tournamentId, statKey)
                .orElse(null);
        String label = def != null ? def.getRankingLabel() : null;

        Page<TournamentIndividualRankingEntity> page =
                rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(tournamentId, statKey, pageable);

        NameResolutionContext ctx = buildNameResolutionContext(tournamentId, viewerUserId, page.getContent());
        return page.map(entity ->
                mapper.toIndividualRankingResponse(entity, label, ctx.resolve(entity.getUserId())));
    }

    /**
     * 全ランキング一覧（項目別リーダー）を取得する（F08.7 順位UI 項目①: 選手名を F19.1 経由で解決）。
     *
     * @param tournamentId 大会 ID
     * @param viewerUserId 閲覧者 user_id（未ログインなら {@code null}）
     */
    public RankingSummaryResponse getRankingSummary(Long tournamentId, Long viewerUserId) {
        List<TournamentStatDefEntity> defs =
                statDefRepository.findByTournamentIdAndIsRankingTargetTrueOrderBySortOrderAsc(tournamentId);

        // 各カテゴリのリーダー（rank=1）を集めて一括で名前解決する（N+1 回避）。
        List<TournamentIndividualRankingEntity> leaders = new ArrayList<>();
        Map<String, TournamentIndividualRankingEntity> leaderByStatKey = new HashMap<>();
        for (TournamentStatDefEntity def : defs) {
            List<TournamentIndividualRankingEntity> top =
                    rankingRepository.findByTournamentIdAndStatKeyOrderByRankAsc(tournamentId, def.getStatKey());
            if (!top.isEmpty()) {
                leaders.add(top.get(0));
                leaderByStatKey.put(def.getStatKey(), top.get(0));
            }
        }

        NameResolutionContext ctx = buildNameResolutionContext(tournamentId, viewerUserId, leaders);

        List<RankingSummaryResponse.RankingCategory> categories = defs.stream()
                .map(def -> {
                    TournamentIndividualRankingEntity leaderEntity = leaderByStatKey.get(def.getStatKey());
                    IndividualRankingResponse leader = leaderEntity == null ? null
                            : mapper.toIndividualRankingResponse(leaderEntity, def.getRankingLabel(),
                                    ctx.resolve(leaderEntity.getUserId()));
                    return new RankingSummaryResponse.RankingCategory(
                            def.getStatKey(), def.getName(), def.getRankingLabel(), def.getUnit(), leader);
                })
                .toList();

        return new RankingSummaryResponse(categories);
    }

    // ===== F08.7 項目①: 選手名解決（F19.1 本人可視性経由） =====

    /**
     * ランキング行群の選手名を F19.1 本人可視性経由で一括解決し、userId → {@link DisplayIdentity} の
     * 解決結果を保持する {@link NameResolutionContext} を構築する。
     *
     * <p>処理手順:</p>
     * <ol>
     *   <li>大会の主催 ORG とその {@code supporter_name_disclosure} 設定（{@link ScopeSettings}）を解決する</li>
     *   <li>{@code viewerUserId} と ORG スコープから {@link ViewerContext} を構築する（閲覧者立場の解決）</li>
     *   <li>行群の userId 集合を {@link UserRepository#findByIdIn(Collection)} で一括取得（N+1 回避）</li>
     *   <li>各 userId について {@link PostAuthor} を組み立て {@link IdentityVisibilityResolver} を適用</li>
     * </ol>
     *
     * <p>大会・ORG が解決できない異常時は、安全側として全選手を匿名化（汎用ラベル）した
     * {@link NameResolutionContext} を返す（無条件 displayName 露出を避ける）。</p>
     */
    private NameResolutionContext buildNameResolutionContext(
            Long tournamentId, Long viewerUserId, List<TournamentIndividualRankingEntity> rows) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null || tournament.getOrganizationId() == null) {
            log.warn("ランキング名前解決: 大会/主催ORGが解決できないため全選手を匿名化する。tournamentId={}",
                    tournamentId);
            return NameResolutionContext.allAnonymous();
        }
        Long organizationId = tournament.getOrganizationId();

        ScopeRef scope = ScopeRef.ofOrganization(organizationId);
        ScopeSettings settings = resolveScopeSettings(organizationId);
        ViewerContext viewer = viewerContextBuilder.buildForOrganizationByUserId(viewerUserId, organizationId);

        Set<Long> userIds = rows.stream()
                .map(TournamentIndividualRankingEntity::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserEntity> users = bulkLoadUsers(userIds);

        Map<Long, DisplayIdentity> resolved = new HashMap<>();
        for (Long userId : userIds) {
            UserEntity user = users.get(userId);
            PostAuthor author = toPostAuthor(userId, user);
            resolved.put(userId, identityVisibilityResolver.resolveIdentityForViewer(
                    author, viewer, scope, settings));
        }
        return new NameResolutionContext(resolved);
    }

    /**
     * 主催 ORG の {@code supporter_name_disclosure} 設定を {@link ScopeSettings} として解決する。
     * ORG が見つからない / 未設定の場合は {@link NameDisclosureMode#DISPLAY_NAME} を既定値とする。
     */
    private ScopeSettings resolveScopeSettings(Long organizationId) {
        OrganizationEntity org = organizationRepository.findById(organizationId).orElse(null);
        NameDisclosureMode mode = (org != null && org.getSupporterNameDisclosure() != null)
                ? org.getSupporterNameDisclosure()
                : NameDisclosureMode.DISPLAY_NAME;
        return new ScopeSettings(mode);
    }

    private Map<Long, UserEntity> bulkLoadUsers(Set<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, UserEntity> result = new HashMap<>();
        for (UserEntity u : userRepository.findByIdIn(userIds)) {
            result.put(u.getId(), u);
        }
        return result;
    }

    /**
     * userId と取得済み {@link UserEntity} から {@link PostAuthor} を組み立てる。
     *
     * <p>ユーザーが取得できない場合（退会・物理削除等）は authorId のみを持つ匿名相当の PostAuthor を
     * 返し、Resolver 側で「退会済みユーザー」相当のフォールバックに委ねる
     * （{@code displayName=null} のため §4.6.4 フォールバックが効く）。
     * {@code care_category == MINOR} の選手は Resolver が §11.3 で強制匿名化する。</p>
     */
    private PostAuthor toPostAuthor(Long userId, UserEntity user) {
        if (user == null) {
            return new PostAuthor(userId, null, null, null, null, false);
        }
        boolean isMinor = CareCategory.MINOR == user.getCareCategory();
        return new PostAuthor(
                userId,
                user.getDisplayName(),
                null, // ランキングは投稿時スナップショットを持たないため現在値で解決する
                buildFullName(user),
                user.getAvatarUrl(),
                isMinor);
    }

    /**
     * {@link UserEntity} から本名（lastName + firstName）を組み立てる。null は非 null 側のみ返す。
     */
    private static String buildFullName(UserEntity user) {
        String last = user.getLastName();
        String first = user.getFirstName();
        if (last == null && first == null) {
            return null;
        }
        if (last == null) {
            return first;
        }
        if (first == null) {
            return last;
        }
        return last + first;
    }

    /**
     * userId → {@link DisplayIdentity} の解決結果を保持し、行ごとに名前を引き当てるコンテキスト。
     */
    private static final class NameResolutionContext {
        private final Map<Long, DisplayIdentity> byUserId;

        private NameResolutionContext(Map<Long, DisplayIdentity> byUserId) {
            this.byUserId = byUserId;
        }

        /** 全選手を匿名化扱いとする安全側コンテキスト（大会/ORG 解決不能時）。 */
        static NameResolutionContext allAnonymous() {
            return new NameResolutionContext(Map.of());
        }

        /**
         * userId の解決済み表示識別を返す。未解決（マップ不在）の場合は安全側として匿名化済み
         * {@link DisplayIdentity}（汎用ラベル「投稿者」+ 汎用アバター）を返す。
         */
        DisplayIdentity resolve(Long userId) {
            DisplayIdentity identity = userId == null ? null : byUserId.get(userId);
            if (identity != null) {
                return identity;
            }
            return new DisplayIdentity(
                    AnonymousLabels.POSTER,
                    DisplayIdentity.ANONYMOUS_AVATAR_URL,
                    false,
                    true);
        }
    }

    private static class PlayerAggregation {
        final Long userId;
        final Long participantId;
        int matchCount = 0;
        List<Integer> intValues = new ArrayList<>();
        List<BigDecimal> decimalValues = new ArrayList<>();
        Integer totalInt = null;
        BigDecimal totalDecimal = null;

        PlayerAggregation(Long userId, Long participantId) {
            this.userId = userId;
            this.participantId = participantId;
        }
    }
}
