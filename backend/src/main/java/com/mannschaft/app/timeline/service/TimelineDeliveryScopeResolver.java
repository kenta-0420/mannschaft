package com.mannschaft.app.timeline.service;

import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.timeline.PostDeliveryScope;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配下配信（{@link PostDeliveryScope}）で「閲覧者に届く上位組織」を求める唯一の正準実装。
 *
 * <p><b>なぜ 1 箇所に集めるのか</b>: 配信範囲は<b>可視性の拡大</b>である。フィードにだけ適用して
 * 詳細取得・ユーザー投稿一覧・検索に適用し忘れると「フィードには出るのに直リンクでは 404」という
 * 非対称が生まれる。逆にどこかで広く取りすぎると漏洩する。展開規則を複数箇所に書くと必ずズレるため、
 * 以下 3 経路はすべて本クラスが返す集合を使う:</p>
 * <ul>
 *   <li>{@code TimelinePostRepository#findMyFeed}（マイフィード）</li>
 *   <li>{@code TimelinePostRepository#findByUserIdVisibleToCaller}（ユーザー投稿一覧）</li>
 *   <li>{@code TimelinePostRepository#searchByKeyword}（検索）</li>
 *   <li>{@link TimelinePostVisibilityAccessGuard#isVisible}（詳細取得・投票・みたよ！・
 *       ブックマーク・返信の共通入口）</li>
 * </ul>
 *
 * <p><b>距離の規則</b>（{@link Reach} 参照）:</p>
 * <ul>
 *   <li>距離 1 の祖先組織 … {@code CHILDREN} と {@code DESCENDANTS} の<b>両方</b>が届く</li>
 *   <li>距離 2 以上の祖先組織 … {@code DESCENDANTS} <b>のみ</b>が届く</li>
 *   <li>距離 0（直接所属）… 配信指定に関係なく届く。これは本クラスの担当外で、
 *       呼び出し側の「直接所属」述語が担当する（述語を統合してはならない。統合すると
 *       「直接所属なら DIRECT でも見える／祖先経由なら配信指定時のみ」が表現できなくなる）。</li>
 * </ul>
 *
 * <p><b>閲覧者の起点</b>は「自分の所属組織」∪「自分の所属チームのアンカー組織」である。
 * チーム加入時に上位組織の membership は自動生成されないため、アンカー組織を辿らないと
 * チームにのみ所属するユーザーへ組織の周知が届かない。アンカー組織<b>自身</b>も距離 1 として
 * 扱う（チームはアンカー組織の一段下に位置すると見なす）。これにより、アンカー組織が
 * {@code CHILDREN} で出した周知はチーム所属者に届き、{@code DIRECT} で出した周知は届かない
 * （＝現行挙動を変えない）。</p>
 *
 * <p><b>ミュートは本クラスの関心事ではない</b>。ミュートは認可ではなく表示設定であり、
 * {@code findMyFeed} の中だけに置く。ここへ持ち込むと「ミュートしたら閲覧権限まで失う」ことになる。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineDeliveryScopeResolver {

    /**
     * 空コレクションを JPQL / native SQL の {@code IN ()} に渡すと構文エラーになるためのダミー値。
     *
     * <p><b>{@code IN} と {@code NOT IN} で意味が反転する</b>ことに注意（{@code findMyFeed} の
     * Javadoc にも明記）。scope ID は常に正の値なので:</p>
     * <ul>
     *   <li>{@code scope_id IN (-1)} … 1 件もマッチしない＝当該 OR 条件を無効化する（配信対象なし）</li>
     *   <li>{@code scope_id NOT IN (-1)} … 全件マッチする＝除外が働かない（ミュートなし）</li>
     * </ul>
     * 取り違えると「全件消える」「全件通る」のどちらかに倒れるため、必ず用途に応じて使い分けること。
     */
    public static final Long EMPTY_IN_SENTINEL = -1L;

    private final MembershipService membershipService;
    private final OrganizationHierarchyService organizationHierarchyService;

    /**
     * 配下配信で閲覧者に届く上位組織の ID 集合（距離別）。
     *
     * @param nearOrgIds 距離 1 の祖先組織 ID（{@code CHILDREN} / {@code DESCENDANTS} が届く）
     * @param farOrgIds  距離 2 以上の祖先組織 ID（{@code DESCENDANTS} のみ届く）
     */
    public record Reach(List<Long> nearOrgIds, List<Long> farOrgIds) {

        /** 配信で届く上位組織が 1 件も無い（＝配下配信の述語が一切効かない）か。 */
        public boolean isEmpty() {
            return nearOrgIds.isEmpty() && farOrgIds.isEmpty();
        }

        /** {@code IN} 句へ安全に渡せる距離 1 集合（空ならダミー値で当該条件を無効化する）。 */
        public List<Long> safeNearOrgIds() {
            return nearOrgIds.isEmpty() ? List.of(EMPTY_IN_SENTINEL) : nearOrgIds;
        }

        /** {@code IN} 句へ安全に渡せる距離 2 以上集合（空ならダミー値で当該条件を無効化する）。 */
        public List<Long> safeFarOrgIds() {
            return farOrgIds.isEmpty() ? List.of(EMPTY_IN_SENTINEL) : farOrgIds;
        }
    }

    /**
     * 閲覧者の所属から配信到達範囲を解決する（所属解決込み）。
     *
     * @param userId 閲覧者ユーザー ID
     * @return 距離別の上位組織 ID 集合
     */
    public Reach resolve(Long userId) {
        if (userId == null) {
            return new Reach(List.of(), List.of());
        }
        return resolve(membershipService.getActiveTeamIdsByUser(userId),
                membershipService.getActiveOrgIdsByUser(userId));
    }

    /**
     * 閲覧者の所属チーム/組織から配信到達範囲を解決する（所属を既に解決済みの呼び出し元向け）。
     *
     * @param teamIds 閲覧者の所属チーム ID
     * @param orgIds  閲覧者の所属組織 ID
     * @return 距離別の上位組織 ID 集合
     */
    public Reach resolve(List<Long> teamIds, List<Long> orgIds) {
        List<Long> anchorOrgIds = organizationHierarchyService.getAnchorOrgIdsByTeamIds(teamIds);

        // 起点＝直接所属組織 ∪ チームのアンカー組織
        Set<Long> startOrgIds = new HashSet<>();
        if (orgIds != null) {
            orgIds.stream().filter(java.util.Objects::nonNull).forEach(startOrgIds::add);
        }
        startOrgIds.addAll(anchorOrgIds);

        if (startOrgIds.isEmpty()) {
            return new Reach(List.of(), List.of());
        }

        Map<Long, Integer> ancestorDepths = organizationHierarchyService
                .getAncestorOrgIdsWithDepth(startOrgIds);

        Set<Long> near = new HashSet<>();
        Set<Long> far = new HashSet<>();

        // アンカー組織自身は距離 1 として扱う（チームはアンカー組織の一段下）。
        // ただし閲覧者が同じ組織に直接所属している場合は距離 0（直接所属述語の担当）なので含めない。
        for (Long anchorOrgId : anchorOrgIds) {
            if (orgIds == null || !orgIds.contains(anchorOrgId)) {
                near.add(anchorOrgId);
            }
        }

        for (Map.Entry<Long, Integer> e : ancestorDepths.entrySet()) {
            Long orgId = e.getKey();
            // 直接所属している組織は距離 0 扱い（直接所属述語が担当）。配信述語には載せない。
            if (orgIds != null && orgIds.contains(orgId)) {
                continue;
            }
            if (e.getValue() == 1) {
                near.add(orgId);
            } else {
                far.add(orgId);
            }
        }
        // 距離 1 で到達できるなら近い方を優先する（CHILDREN も届く方が正しい）
        far.removeAll(near);

        return new Reach(new ArrayList<>(near), new ArrayList<>(far));
    }

    /**
     * 投稿 1 件が配下配信によって閲覧者に届くかを判定する（{@code isVisible} 用）。
     *
     * <p>直接所属の判定は行わない（呼び出し側が先に評価する）。ORGANIZATION スコープ以外は
     * 常に {@code false}（チームに階層が無いため配下配信は成立しない）。</p>
     *
     * @param post   判定対象の投稿（DB 由来の実体）
     * @param userId 閲覧者ユーザー ID
     * @return 配下配信で届くなら true
     */
    public boolean isDeliveredTo(TimelinePostEntity post, Long userId) {
        if (post == null || userId == null) {
            return false;
        }
        if (post.getScopeType() != PostScopeType.ORGANIZATION) {
            return false;
        }
        PostDeliveryScope deliveryScope = post.getDeliveryScope();
        if (deliveryScope == null || deliveryScope == PostDeliveryScope.DIRECT) {
            return false;
        }
        Reach reach = resolve(userId);
        if (reach.nearOrgIds().contains(post.getScopeId())) {
            // 距離 1 には CHILDREN / DESCENDANTS の両方が届く
            return true;
        }
        // 距離 2 以上には DESCENDANTS のみ
        return deliveryScope == PostDeliveryScope.DESCENDANTS
                && reach.farOrgIds().contains(post.getScopeId());
    }
}
