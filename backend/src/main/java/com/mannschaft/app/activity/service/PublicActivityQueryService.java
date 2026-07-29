package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.publicview.dto.PublicActivityDetail;
import com.mannschaft.app.publicview.dto.PublicActivitySummary;
import com.mannschaft.app.publicview.dto.PublicScopeRef;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * F06.4 公開活動記録 クエリサービス（<b>匿名公開経路の唯一の入口</b>）。
 *
 * <p>金型: {@code PublicPostQueryService}（F19.1）。可視性判定・親スコープ検証・
 * 公開専用 DTO への変換を 1 箇所に集約し、Controller は薄い受け皿に徹する。</p>
 *
 * <h2>匿名公開で守るべき 5 つの門（すべてを通ったものだけ 200 で返す）</h2>
 * <ol>
 *   <li><b>親スコープが公開であること</b> — チーム / 組織が {@code visibility=PUBLIC} かつ
 *       未凍結（{@code archived_at IS NULL}）・未削除（{@code deleted_at IS NULL}）。
 *       他の公開系（{@code PublicPostQueryService}）が必ず {@code findPublicTeamById} /
 *       {@code findPublicOrganizationById} を先に引くのと同じ流儀。これを怠ると
 *       「非公開チームの中身が PUBLIC 設定のまま漏れる」。F00 は親チーム / 組織の公開設定を
 *       判定材料に持たないため、本サービスが自前で前置する。</li>
 *   <li><b>記録自身が公開済みであること</b> — {@code visibility=PUBLIC} かつ
 *       {@code status=PUBLISHED}。論理削除済みは {@code @SQLRestriction} が自動除外する。</li>
 *   <li><b>F00 正準の可視性判定を通ること</b> — {@link ContentVisibilityChecker}（未認証 userId=null）。
 *       可視性判定の単一真実源は F00 側であり、独自述語は作らない。</li>
 *   <li><b>パス変数と実スコープが一致すること</b> — {@code /teams/{teamId}/activities/{id}} に
 *       他チーム・他組織の記録 ID を渡す<b>スコープ詐称</b>を拒否する。</li>
 *   <li><b>公開してよい項目だけを返すこと</b> — {@link PublicActivityDetail} /
 *       {@link PublicActivitySummary}（御裁可済み 8 項目）へ詰め替える。Entity も
 *       認証済み DTO も外へ出さない。</li>
 * </ol>
 *
 * <h2>失敗はすべて 404（存在秘匿）</h2>
 * <p>上記いずれの門で落ちても {@link PublicViewErrorCode#PUBLIC_013} 一本に倒す。
 * 旧実装は非公開記録に {@code VISIBILITY_001} → <b>403</b> を返しており、
 * 「存在するが権限がない」ことを漏らす<b>存在オラクル</b>になっていた。
 * 攻撃者が ID を総当りして実在 ID を列挙できてしまうため、他の公開系と同じく一律 404 とする。</p>
 *
 * <h2>ドメイン境界について（CLAUDE.md 原則 5）</h2>
 * <p>本サービスは activity ドメインに属しつつ team / organization ドメインの
 * <b>Service</b>（{@link TeamService#findPublicTeamNameById(Long)} /
 * {@link OrganizationService#findPublicOrganizationNameById(Long)}）を呼ぶ。
 * 「親スコープが公開か」は親ドメインしか知り得ない知識であり、越境は避けられない。
 * ただし以下を守って越境の影響を最小化している:</p>
 * <ul>
 *   <li>別ドメインの <b>Repository / Entity は参照しない</b>（番人 D-1 / D-5）。
 *       親ドメインからはスコープ表示名（{@code String}）のみを受け取る</li>
 *   <li>読み取り専用（{@code @Transactional(readOnly = true)}）であり、
 *       複数ドメインへ書き込むトランザクションは張らない</li>
 * </ul>
 * <p>将来スコープ公開設定をイベント駆動でレプリケーションできるようになれば、
 * この越境呼び出しはローカル参照へ置き換えられる。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PublicActivityQueryService {

    /** 一覧の 1 リクエストあたり最大件数（深いページネーション・DoS 抑止）。金型: PublicTeamPostController。 */
    public static final int MAX_PAGE_SIZE = 100;

    /** 一覧の既定件数（{@code limit} 未指定・0 以下のとき）。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final ActivityResultService activityResultService;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    // ────────────────────────────────────────────────────────────
    // 詳細
    // ────────────────────────────────────────────────────────────

    /**
     * 活動記録を ID 直引きで取得する（スコープ不問・SNS シェア用）。
     *
     * <p>パス変数にスコープが無いため、記録自身のスコープを引き当てて
     * その親が公開かどうかを検証する。</p>
     *
     * @throws BusinessException 公開対象でない場合（{@link PublicViewErrorCode#PUBLIC_013} → 404）
     */
    public PublicActivityDetail getPublicActivityById(Long id) {
        ActivityResultEntity entity = loadPublicActivityOrThrow(id);
        PublicScopeRef scopeRef = resolvePublicScopeRefOrThrow(
                entity.getScopeType(), entity.getScopeId());
        return toDetail(entity, scopeRef);
    }

    /**
     * チーム配下の活動記録詳細を取得する。
     *
     * <p>パス変数 {@code teamId} と記録の {@code scopeType} / {@code scopeId} を照合し、
     * 不一致なら 404（スコープ詐称拒否）。</p>
     *
     * @throws BusinessException 公開対象でない / スコープ不一致の場合（404）
     */
    public PublicActivityDetail getPublicTeamActivity(Long teamId, Long id) {
        PublicScopeRef scopeRef = resolvePublicScopeRefOrThrow(ActivityScopeType.TEAM, teamId);
        ActivityResultEntity entity = loadPublicActivityOrThrow(id);
        assertScopeMatches(entity, ActivityScopeType.TEAM, teamId);
        return toDetail(entity, scopeRef);
    }

    /**
     * 組織配下の活動記録詳細を取得する。
     *
     * @throws BusinessException 公開対象でない / スコープ不一致の場合（404）
     */
    public PublicActivityDetail getPublicOrganizationActivity(Long orgId, Long id) {
        PublicScopeRef scopeRef = resolvePublicScopeRefOrThrow(
                ActivityScopeType.ORGANIZATION, orgId);
        ActivityResultEntity entity = loadPublicActivityOrThrow(id);
        assertScopeMatches(entity, ActivityScopeType.ORGANIZATION, orgId);
        return toDetail(entity, scopeRef);
    }

    // ────────────────────────────────────────────────────────────
    // 一覧
    // ────────────────────────────────────────────────────────────

    /**
     * チーム配下の公開活動記録一覧を取得する。
     *
     * <p>親チームが公開でなければ 404（一覧でも存在秘匿する。空配列を返すと
     * 「そのチームは存在するが記録が無い」ことを漏らす）。</p>
     *
     * @param teamId 対象チーム ID
     * @param limit  取得件数（{@code 0} 以下は {@value #DEFAULT_PAGE_SIZE}、
     *               {@value #MAX_PAGE_SIZE} 超は {@value #MAX_PAGE_SIZE} に丸める）
     */
    public List<PublicActivitySummary> listPublicTeamActivities(Long teamId, int limit) {
        PublicScopeRef scopeRef = resolvePublicScopeRefOrThrow(ActivityScopeType.TEAM, teamId);
        return listPublicActivities(ActivityScopeType.TEAM, teamId, limit, scopeRef);
    }

    /**
     * 組織配下の公開活動記録一覧を取得する。
     *
     * @param orgId 対象組織 ID
     * @param limit 取得件数（丸め規則は {@link #listPublicTeamActivities(Long, int)} と同じ）
     */
    public List<PublicActivitySummary> listPublicOrganizationActivities(Long orgId, int limit) {
        PublicScopeRef scopeRef = resolvePublicScopeRefOrThrow(
                ActivityScopeType.ORGANIZATION, orgId);
        return listPublicActivities(ActivityScopeType.ORGANIZATION, orgId, limit, scopeRef);
    }

    // ────────────────────────────────────────────────────────────
    // 内部ヘルパ
    // ────────────────────────────────────────────────────────────

    /**
     * スコープ配下の公開記録を取得して公開 DTO へ詰め替える。
     *
     * <p><b>N+1 禁止</b>: 親スコープの公開性・表示名は<b>ループの外で 1 回だけ</b>解決し、
     * 全要素で同じ {@link PublicScopeRef} を共有する。記録ごとに親を引くと件数に比例して
     * SQL が増える（契約テスト AC-28 が件数を変えて SQL 数の不変性を検証している）。</p>
     */
    private List<PublicActivitySummary> listPublicActivities(
            ActivityScopeType scopeType, Long scopeId, int limit, PublicScopeRef scopeRef) {
        Page<ActivityResultEntity> page =
                activityResultService.listPublicActivities(scopeType, scopeId, toPageable(limit));
        return page.getContent().stream()
                .map(entity -> toSummary(entity, scopeRef))
                .toList();
    }

    /**
     * {@code limit} クエリパラメータを安全な {@link Pageable} に丸める。
     *
     * <p>旧実装は {@code PageRequest.of(0, limit)} をそのまま渡しており、
     * {@code limit=100000} で全件取得できる DoS 経路になっていた。さらに {@code limit=0} は
     * {@link IllegalArgumentException} → 500 になっていた（未認証で 500 を誘発できる状態）。</p>
     */
    static Pageable toPageable(int limit) {
        int size = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        return PageRequest.of(0, size);
    }

    /**
     * 公開してよい活動記録を取得する。取得できない理由は一切区別せず 404 に倒す。
     *
     * <p>二重チェック（多層防御）:</p>
     * <ol>
     *   <li>{@link ActivityResultService#findPublicActivityById(Long)}
     *       — {@code visibility=PUBLIC} かつ {@code status=PUBLISHED}（論理削除は SQL 制約で除外）</li>
     *   <li>{@link ContentVisibilityChecker#canView(ReferenceType, Long, Long)}
     *       — F00 正準の可視性判定（未認証 {@code userId=null}）。
     *       {@code assertCanView} は非公開に 403 を投げてしまうため、存在秘匿の観点から
     *       <b>例外を投げない {@code canView} を使い、自前で 404 に正規化する</b></li>
     * </ol>
     */
    private ActivityResultEntity loadPublicActivityOrThrow(Long id) {
        if (id == null) {
            throw notFound();
        }
        ActivityResultEntity entity = activityResultService.findPublicActivityById(id)
                .orElseThrow(PublicActivityQueryService::notFound);
        if (!contentVisibilityChecker.canView(ReferenceType.ACTIVITY_RESULT, id, null)) {
            // F00 が拒否した（＝可視性の正準が非公開と判断した）。403 ではなく 404 で秘匿する。
            throw notFound();
        }
        return entity;
    }

    /**
     * パス変数のスコープと記録の実スコープが一致することを検証する（スコープ詐称拒否）。
     *
     * <p>照合を怠ると {@code /organizations/{任意の公開組織ID}/activities/{他チームの記録ID}} が
     * 200 を返し、URL のスコープを詐称して任意の公開記録を引ける（IDOR）。</p>
     */
    private void assertScopeMatches(ActivityResultEntity entity,
                                    ActivityScopeType expectedType, Long expectedScopeId) {
        if (entity.getScopeType() != expectedType
                || !java.util.Objects.equals(entity.getScopeId(), expectedScopeId)) {
            log.debug("公開活動記録: スコープ詐称を拒否 activityId={} 実際={}:{} 要求={}:{}",
                    entity.getId(), entity.getScopeType(), entity.getScopeId(),
                    expectedType, expectedScopeId);
            throw notFound();
        }
    }

    /**
     * 親スコープが匿名公開してよい状態かを検証し、公開用スコープ参照を組み立てる。
     *
     * <p>{@link ActivityScopeType#COMMITTEE} は公開ページを持たないため常に 404
     * （fail-closed。将来公開対象になったらここに分岐を追加する）。</p>
     */
    private PublicScopeRef resolvePublicScopeRefOrThrow(ActivityScopeType scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            throw notFound();
        }
        Optional<PublicScopeRef> scopeRef = switch (scopeType) {
            case TEAM -> teamService.findPublicTeamNameById(scopeId)
                    .map(name -> PublicScopeRef.ofTeam(scopeId, name));
            case ORGANIZATION -> organizationService.findPublicOrganizationNameById(scopeId)
                    .map(name -> PublicScopeRef.ofOrganization(scopeId, name));
            case COMMITTEE -> Optional.empty();
        };
        return scopeRef.orElseThrow(PublicActivityQueryService::notFound);
    }

    /** Entity → 公開詳細 DTO（御裁可済み 8 項目のみ）。 */
    private static PublicActivityDetail toDetail(ActivityResultEntity entity, PublicScopeRef scopeRef) {
        return new PublicActivityDetail(
                entity.getId(),
                entity.getTitle(),
                entity.getActivityDate(),
                entity.getActivityTimeStart(),
                entity.getActivityTimeEnd(),
                entity.getDescription(),
                scopeRef,
                entity.getCreatedAt());
    }

    /** Entity → 公開一覧 DTO（御裁可済み 8 項目のみ）。 */
    private static PublicActivitySummary toSummary(ActivityResultEntity entity, PublicScopeRef scopeRef) {
        return new PublicActivitySummary(
                entity.getId(),
                entity.getTitle(),
                entity.getActivityDate(),
                entity.getActivityTimeStart(),
                entity.getActivityTimeEnd(),
                entity.getDescription(),
                scopeRef,
                entity.getCreatedAt());
    }

    /**
     * 匿名公開経路の<b>唯一の失敗</b>。理由を問わず同一コード・同一メッセージで 404 にする
     * （ステータスもボディも区別できないことが列挙オラクル封じの本体）。
     */
    private static BusinessException notFound() {
        return new BusinessException(PublicViewErrorCode.PUBLIC_013);
    }
}
