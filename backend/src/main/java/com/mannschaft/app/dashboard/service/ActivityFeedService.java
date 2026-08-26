package com.mannschaft.app.dashboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.ActivityType;
import com.mannschaft.app.dashboard.DashboardMapper;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.TargetType;
import com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.dto.ScheduleFeedDetail;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.schedule.visibility.ScheduleVisibilityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * アクティビティフィードのクエリサービス。
 * 個人ダッシュボードの「最近のアクティビティ」ウィジェットのデータ取得を担当する。
 * 書き込みは ActivityFeedEventListener が @Async で行う（別クラス）。
 *
 * <p><strong>F03.18 §4.2 — 可視性フィルタの一元実装</strong>。SCHEDULE 系の行は
 * 対象予定そのものの可視性に従って隠れなければならない（予定の存在とタイトルが
 * 所属外へ漏れる）。判定は {@link ScheduleVisibilityResolver#filterAccessible} に
 * <strong>一元化</strong>し、本クラスにも Repository にも独自の閲覧述語を置かない。
 * 参照のたびに Resolver を通すため、予定が後から非公開化されても既発行の行は
 * 自動的に消える（作成時スナップショット方式ではない）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ActivityFeedService {

    private final ActivityFeedRepository activityFeedRepository;
    private final DashboardMapper dashboardMapper;
    private final NameResolverService nameResolverService;
    private final ScheduleVisibilityResolver scheduleVisibilityResolver;

    /**
     * {@code detail} JSON のパース専用 ObjectMapper。
     * 素の JSON → DTO 変換のみを行い、アプリ既定 Mapper の設定（日時フォーマット等）に
     * 依存しないため定数として保持する（DI にするとモック注入を強いられ、
     * 「パースされたか」の検証が偽物になる）。
     */
    private static final ObjectMapper DETAIL_OBJECT_MAPPER = new ObjectMapper();

    /** アクティビティ取得のデフォルト件数 */
    private static final int DEFAULT_LIMIT = 10;
    /** アクティビティ取得の最大件数 */
    private static final int MAX_LIMIT = 50;

    /**
     * 可視性で行が除かれた分を埋めるための Repository フェッチ回数の上限（F03.18 §4.2 手順5）。
     * 可視性で大半が弾かれる極端なケースでもレスポンス時間を保証するための安全弁であり、
     * 上限で打ち切った場合は {@code nextCursor} を非 null にして「まだ続きがある」と伝える。
     */
    static final int MAX_FETCH_LOOPS = 5;

    /**
     * 空の所属リストを JPQL の {@code IN} に渡すと構文エラーになるため詰めるセンチネル。
     * 実在しない負の ID であり、どの行にもマッチしない。
     */
    private static final Long NO_MATCH_SENTINEL = -1L;

    /** 可視性フィルタで行を落としたことを示す内部ログの識別子（クライアント応答は伴わない）。 */
    private static final String LOG_TAG_VISIBILITY_FILTERED = "activity-feed.visibility-filtered";

    /**
     * 所属スコープを横断してアクティビティフィードを取得する。
     * 自分の行動は含まない（actor_id != userId でフィルタ）。
     *
     * <p>SCHEDULE 系の行は {@link ScheduleVisibilityResolver} を通し、閲覧できない予定の行を
     * 除外する。除外で件数が不足した分は次カーソルで追加フェッチして埋める
     * （{@link #MAX_FETCH_LOOPS} 周まで）。</p>
     *
     * @param userId  ユーザーID（＝視聴者）
     * @param cursor  カーソル（アクティビティID。nullの場合は最新から）
     * @param limit   取得件数
     * @param teamIds 視聴者の所属チームID一覧
     * @param orgIds  視聴者の所属組織ID一覧
     */
    public ActivityFeedPageResponse getActivityFeed(
            Long userId, Long cursor, Integer limit, List<Long> teamIds, List<Long> orgIds) {

        int resolvedLimit = resolveLimit(limit);
        List<Long> safeTeamIds = orSentinel(teamIds);
        List<Long> safeOrgIds = orSentinel(orgIds);

        // 所属がまったく無ければ、そもそも読める行が存在しない。
        if (safeTeamIds.equals(List.of(NO_MATCH_SENTINEL)) && safeOrgIds.equals(List.of(NO_MATCH_SENTINEL))) {
            return ActivityFeedPageResponse.empty();
        }

        List<ActivityFeedEntity> visibleRows = new ArrayList<>();
        Long fetchCursor = cursor;
        Long lastRawId = null;
        boolean exhausted = false;
        int loops = 0;

        while (visibleRows.size() < resolvedLimit && loops < MAX_FETCH_LOOPS) {
            loops++;
            List<ActivityFeedEntity> batch = fetchBatch(
                    userId, fetchCursor, resolvedLimit, safeTeamIds, safeOrgIds);

            if (batch.isEmpty()) {
                // Repository が尽きた。続きは無い。
                exhausted = true;
                break;
            }

            // 次カーソルは «フィルタ前» の最終行 id。フィルタ後の id を使うと、
            // 次回リクエストが除外済みの区間を読み直して整合が崩れる（§4.2 手順6）。
            lastRawId = batch.get(batch.size() - 1).getId();
            fetchCursor = lastRawId;

            visibleRows.addAll(filterVisible(batch, userId));

            if (batch.size() < resolvedLimit) {
                // 要求件数に満たない＝母集団が尽きた。
                exhausted = true;
                break;
            }
        }

        // 追加フェッチで超過した分は切り詰める。
        boolean truncated = visibleRows.size() > resolvedLimit;
        List<ActivityFeedEntity> pageRows = truncated
                ? new ArrayList<>(visibleRows.subList(0, resolvedLimit))
                : visibleRows;

        // 次カーソルの算出（§4.2 手順6 ＋ 切り詰め時の是正）。
        //
        // 通常は «フィルタ前» の最終 id を返す。フィルタ後の id を使うと除外済み区間を
        // 読み直してしまうためである。ただし «切り詰めが起きた場合» はこれが欠落を生む:
        // 捨てた可視行は «フィルタ前» 最終 id より新しい（id が大きい）ため、
        // 次回 `id < lastRawId` で二度と現れず、恒久的に失われる。
        // 切り詰め時は「実際に返した最後の行の id」を返す。並びは id DESC なので
        // 次回 `id < nextCursor` が捨てた行の先頭から正しく再開し、重複も欠落も出ない。
        // このとき exhausted（母集団が尽きた）でも «返していない可視行が残っている» ため
        // nextCursor は必ず非 null にする。
        String nextCursor;
        if (truncated) {
            nextCursor = String.valueOf(pageRows.get(pageRows.size() - 1).getId());
        } else {
            nextCursor = (exhausted || lastRawId == null) ? null : String.valueOf(lastRawId);
        }

        return new ActivityFeedPageResponse(toResponses(pageRows), nextCursor);
    }

    /**
     * 1 ページ分を Repository から取得する。カーソルの有無で問い合わせを切り替える。
     */
    private List<ActivityFeedEntity> fetchBatch(
            Long userId, Long cursor, int size, List<Long> teamIds, List<Long> orgIds) {
        PageRequest page = PageRequest.of(0, size);
        if (cursor != null) {
            return activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    teamIds, orgIds, userId, cursor, page);
        }
        return activityFeedRepository.findByScopesAndExcludeActor(teamIds, orgIds, userId, page);
    }

    /**
     * 可視性フィルタ（F03.18 §4.2 手順3〜4）。
     *
     * <p>SCHEDULE を対象とする行の予定 ID を <strong>まとめて1回</strong>
     * {@link ScheduleVisibilityResolver#filterAccessible} に渡す。件数に比例して呼ぶと
     * N+1 になり、可視性判定の SQL 本数上限に反する。</p>
     *
     * <p><strong>削除イベントの例外（マスター裁可）</strong>: {@code ScheduleEntity} は
     * {@code @SQLRestriction("deleted_at IS NULL")} を持つため、論理削除された予定は
     * Resolver の射影に恒久的に載らない。素通しすると {@code SCHEDULE_CANCELLED} が
     * <em>誰にも</em> 見えなくなり、削除の事実そのものが消える。そこで
     * {@code SCHEDULE_CANCELLED} の行だけは Resolver を通さず、
     * <strong>発行時点の scopeType / scopeId への所属</strong>のみで表示可否を決める。
     * 所属の絞り込みは Repository のスコープ条件で既に済んでいるため、ここでは素通しでよい。
     * 「削除された事実は所属者に見せるが、中身は見せない」（{@code detail.fields} は空配列、
     * {@code detail.title} のみ削除直前の値）という活動ログの趣旨と一致する。</p>
     */
    private List<ActivityFeedEntity> filterVisible(List<ActivityFeedEntity> batch, Long viewerUserId) {
        Set<Long> scheduleIdsToCheck = batch.stream()
                .filter(ActivityFeedService::isVisibilityCheckedScheduleRow)
                .map(ActivityFeedEntity::getTargetId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        if (scheduleIdsToCheck.isEmpty()) {
            // SCHEDULE 系が1件も無ければ Resolver を呼ばない（既存7種別だけのページで SQL を増やさない）。
            return batch;
        }

        Set<Long> accessible = scheduleVisibilityResolver.filterAccessible(scheduleIdsToCheck, viewerUserId);

        List<ActivityFeedEntity> kept = new ArrayList<>(batch.size());
        int dropped = 0;
        for (ActivityFeedEntity row : batch) {
            if (!isVisibilityCheckedScheduleRow(row)) {
                kept.add(row);
                continue;
            }
            if (row.getTargetId() != null && accessible.contains(row.getTargetId())) {
                kept.add(row);
            } else {
                // fail-closed: Resolver が返さなかった予定（不可視・実存しない）は落とす。
                dropped++;
            }
        }
        if (dropped > 0) {
            log.debug("{}: viewerUserId={}, dropped={}", LOG_TAG_VISIBILITY_FILTERED, viewerUserId, dropped);
        }
        return kept;
    }

    /**
     * 可視性判定にかけるべき SCHEDULE 行か。
     * SCHEDULE 以外の対象（既存7種別）と、削除イベント {@code SCHEDULE_CANCELLED} は対象外。
     */
    private static boolean isVisibilityCheckedScheduleRow(ActivityFeedEntity row) {
        return row.getTargetType() == TargetType.SCHEDULE
                && row.getActivityType() != ActivityType.SCHEDULE_CANCELLED;
    }

    /**
     * エンティティ群を、actor 名・スコープ名をバッチ解決したうえでレスポンスへ変換する。
     */
    private List<ActivityFeedResponse> toResponses(List<ActivityFeedEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        // actorId のバッチ名前解決
        Set<Long> actorIds = entities.stream()
                .map(ActivityFeedEntity::getActorId)
                .collect(Collectors.toSet());
        Map<Long, String> actorNames = nameResolverService.resolveUserDisplayNames(actorIds);

        // scopeType + scopeId のバッチ名前解決
        Set<Long> teamScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == ScopeType.TEAM)
                .map(ActivityFeedEntity::getScopeId)
                .collect(Collectors.toSet());
        Set<Long> orgScopeIds = entities.stream()
                .filter(e -> e.getScopeType() == ScopeType.ORGANIZATION)
                .map(ActivityFeedEntity::getScopeId)
                .collect(Collectors.toSet());

        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamScopeIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgScopeIds);

        Map<ScopeType, Map<Long, String>> scopeNameMaps = new HashMap<>();
        scopeNameMaps.put(ScopeType.TEAM, teamNames);
        scopeNameMaps.put(ScopeType.ORGANIZATION, orgNames);

        return entities.stream()
                .map(entity -> {
                    String actorDisplayName = actorNames.getOrDefault(entity.getActorId(), "不明なユーザー");
                    String scopeName = scopeNameMaps
                            .getOrDefault(entity.getScopeType(), Map.of())
                            .getOrDefault(entity.getScopeId(), "不明なスコープ");
                    return dashboardMapper.toActivityFeedResponse(
                            entity,
                            new ActivityFeedResponse.ActorSummary(entity.getActorId(), actorDisplayName, null),
                            scopeName,
                            parseDetail(entity)
                    );
                })
                .toList();
    }

    /**
     * {@code detail}（JSON 文字列）を構造化オブジェクトへパースする（F03.18 §3.3 裁定）。
     *
     * <p>Entity は JSON «文字列» を保持するが、API レスポンスは object を返す契約である。
     * 文字列のまま {@code ActivityFeedResponse.detail} に積むと Jackson が
     * エスケープ済みの文字列として出力し、FE から {@code detail.fields} を読めない。</p>
     *
     * <p><strong>パース失敗で行を落とさない</strong>: 壊れた1行がフィード一覧全体を
     * 壊してはならない。WARN で記録し {@code detail = null} として行自体は返す
     * （書き込み側の「シリアライズ失敗時は detail=null で続行」と対になる読み取り側の失敗処理）。</p>
     *
     * @return パース済み detail。detail が無い（既存7種別）／パース失敗時は null
     */
    private Object parseDetail(ActivityFeedEntity entity) {
        String raw = entity.getDetail();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DETAIL_OBJECT_MAPPER.readValue(raw, ScheduleFeedDetail.class);
        } catch (JsonProcessingException e) {
            log.warn("アクティビティフィードの detail パース失敗（該当行は detail=null で返す） activityFeedId={}, error={}",
                    entity.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 空・null のスコープリストを、どの行にもマッチしないセンチネル1件に置き換える
     * （JPQL の {@code IN ()} が構文エラーになるため）。
     */
    private static List<Long> orSentinel(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of(NO_MATCH_SENTINEL);
        }
        return ids;
    }

    /**
     * 取得件数を解決する。
     */
    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
