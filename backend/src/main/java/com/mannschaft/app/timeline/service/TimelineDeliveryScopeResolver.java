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
import java.util.HashMap;
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
 * <p><b>閲覧者の起点は2経路あり、距離の数え方が1段ずれる</b>。チーム加入時に上位組織の
 * membership は自動生成されないため、アンカー組織を辿らないとチームにのみ所属するユーザーへ
 * 組織の周知が届かない。両経路を別々に展開し、同じ組織へ複数経路で届く場合は最小距離を採る。</p>
 * <table border="1">
 *   <caption>閲覧者から見た距離</caption>
 *   <tr><th>経路</th><th>距離0</th><th>距離1</th><th>距離2</th></tr>
 *   <tr><td>直接所属組織 O</td><td>O 自身</td><td>O の親</td><td>O の祖父</td></tr>
 *   <tr><td>チーム T（アンカー組織 A）</td><td>T 自身（組織ではない）</td><td>A</td><td>A の親</td></tr>
 * </table>
 * <p>チームはアンカー組織の<b>一段下</b>に位置すると見なすため、アンカー経路は全体が +1 ずれる。
 * これにより:</p>
 * <ul>
 *   <li>A が {@code CHILDREN} で出した周知は T の所属者に届く（距離1）。</li>
 *   <li>A が {@code DIRECT} で出した周知は T の所属者に届かない（＝現行挙動を変えない）。</li>
 *   <li><b>A の親</b>が {@code CHILDREN} で出した周知は T の所属者に届かない（距離2）。
 *       ここを距離1と数えると「直下の子組織まで」のつもりの周知が一階層余分に流れる。</li>
 * </ul>
 * <p>深度打ち切りは組織階層を何ホップ辿るかの上限（{@code app.org.max-depth}）であり、
 * アンカー組織を起点に数える。したがってチームのみ所属の閲覧者から見た到達距離は
 * 最大で {@code max-depth + 1} になりうる（チーム→アンカーの1ホップ分）。</p>
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

        Set<Long> directOrgIds = new HashSet<>();
        if (orgIds != null) {
            orgIds.stream().filter(java.util.Objects::nonNull).forEach(directOrgIds::add);
        }
        if (directOrgIds.isEmpty() && anchorOrgIds.isEmpty()) {
            return new Reach(List.of(), List.of());
        }

        // 組織ID → 閲覧者から見た距離（最小）。2 経路を別々に展開してから最小距離で畳む。
        Map<Long, Integer> depthByOrgId = new HashMap<>();

        // 経路(a) 直接所属組織を起点にした祖先。直接所属組織自身は距離 0（直接所属述語の担当）
        // なのでここには積まず、その親から距離 1 として積む。
        for (Map.Entry<Long, Integer> e
                : organizationHierarchyService.getAncestorOrgIdsWithDepth(directOrgIds).entrySet()) {
            depthByOrgId.merge(e.getKey(), e.getValue(), Math::min);
        }

        // 経路(b) チームのアンカー組織。チームはアンカー組織の「一段下」に位置すると見なすため、
        // アンカー組織自身が距離 1、アンカー組織の親は距離 2、以降 +1 ずつずれる。
        //
        // ここで +1 のオフセットを掛けるのが要点である。アンカー組織をそのまま
        // getAncestorOrgIdsWithDepth の起点（＝距離 0 の位置）として渡すと、アンカーの親が
        // 距離 1 として返り、本来 DESCENDANTS でしか届かないはずの一段上の組織へ CHILDREN が
        // 過剰配信される（例: 全国連盟→県支部→市支部 で、市支部にアンカーされたチームのみの
        // 所属者へ、県支部の CHILDREN 投稿が届いてしまう）。
        //
        // 深度打ち切りは getAncestorOrgIdsWithDepth 側が「アンカー組織を起点として」
        // app.org.max-depth ホップまでで行う。したがってチームのみ所属の閲覧者から見た
        // 到達距離は最大で max-depth + 1 になりうる（チーム→アンカーの 1 ホップ分）。
        // これは「組織階層を何段辿るか」という上限の意味を保った上での帰結である。
        for (Long anchorOrgId : anchorOrgIds) {
            depthByOrgId.merge(anchorOrgId, 1, Math::min);
        }
        for (Map.Entry<Long, Integer> e
                : organizationHierarchyService.getAncestorOrgIdsWithDepth(anchorOrgIds).entrySet()) {
            depthByOrgId.merge(e.getKey(), e.getValue() + 1, Math::min);
        }

        Set<Long> near = new HashSet<>();
        Set<Long> far = new HashSet<>();
        for (Map.Entry<Long, Integer> e : depthByOrgId.entrySet()) {
            // 直接所属している組織は距離 0 扱い（直接所属述語が担当）。配信述語には載せない。
            if (directOrgIds.contains(e.getKey())) {
                continue;
            }
            if (e.getValue() == 1) {
                near.add(e.getKey());
            } else {
                far.add(e.getKey());
            }
        }

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
