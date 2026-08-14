package com.mannschaft.app.organization.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.dto.AncestorOrganizationResponse;
import com.mannschaft.app.organization.dto.AncestorsResponse;
import com.mannschaft.app.organization.dto.ChildOrganizationResponse;
import com.mannschaft.app.organization.dto.ChildrenResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 組織の階層（祖先・子組織）参照を担当するサービス（F01.2）。
 *
 * <p>{@link OrganizationService} ファサードから委譲される。
 * 祖先チェーン探索（{@code maxDepth} 制限・サイクル検出付き）と
 * 子組織カーソルページングを提供する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class OrganizationHierarchyService {

    private final OrganizationRepository organizationRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final MediaUrlResolver mediaUrlResolver;

    /** 祖先チェーン探索の最大深度。これを超える祖先は返さず {@code truncated: true} を立てる。 */
    @Value("${app.org.max-depth:5}")
    private int maxDepth;

    /** 子組織カーソルページの最大件数。 */
    private static final int CHILDREN_MAX_PAGE_SIZE = 100;

    /** 子組織カーソルページのデフォルト件数。 */
    private static final int CHILDREN_DEFAULT_PAGE_SIZE = 50;

    /**
     * {@code ancestorOrgId} が {@code descendantOrgId} の（直接/間接）祖先かを判定する（org → org）。
     *
     * <p>F08.7.1 リーグ・ピラミッド（§2）が組織階層から上位/下位リーグを導出する際に用いる。
     * 既存 private {@link #hasAncestor(Long, Long)} のサイクル検出・{@code maxDepth} 制限ロジックを
     * そのまま土台にした公開 API。</p>
     *
     * <ul>
     *   <li>自分自身は祖先に含めない（{@code isAncestorOf(X, X)} は常に {@code false}）。</li>
     *   <li>いずれかの引数が {@code null} の場合は安全に {@code false}。</li>
     *   <li>親リンクが辿れない（存在しない/論理削除済み等）場合は {@code false}。</li>
     *   <li>サイクル・{@code maxDepth} 超過でも無限ループせず打ち切って {@code false}。</li>
     * </ul>
     *
     * @param ancestorOrgId   祖先候補の組織ID
     * @param descendantOrgId 子孫候補の組織ID
     * @return {@code ancestorOrgId} が {@code descendantOrgId} の祖先なら {@code true}
     */
    public boolean isAncestorOf(Long ancestorOrgId, Long descendantOrgId) {
        if (ancestorOrgId == null || descendantOrgId == null) {
            return false;
        }
        if (ancestorOrgId.equals(descendantOrgId)) {
            return false; // 自分自身は祖先に含めない
        }
        // descendantOrgId の祖先チェーンに ancestorOrgId が含まれるか
        return hasAncestor(descendantOrgId, ancestorOrgId);
    }

    /**
     * {@code descendantOrgId} が {@code ancestorOrgId} の（直接/間接）子孫かを判定する（org → org）。
     *
     * <p>{@link #isAncestorOf(Long, Long)} の引数を入れ替えた逆引き。判定規則は同じ
     * （自己は子孫に含めない・{@code null} は {@code false}・サイクル/深度超過で停止）。</p>
     *
     * @param descendantOrgId 子孫候補の組織ID
     * @param ancestorOrgId   祖先候補の組織ID
     * @return {@code descendantOrgId} が {@code ancestorOrgId} の子孫なら {@code true}
     */
    public boolean isDescendantOf(Long descendantOrgId, Long ancestorOrgId) {
        return isAncestorOf(ancestorOrgId, descendantOrgId);
    }

    /**
     * 対象組織の祖先チェーン（root → 直近の親 の順）を返す。
     *
     * <p>各祖先は呼び出し者の所属関係と祖先の {@code visibility} / {@code hierarchyVisibility} に応じて
     * フル情報・限定情報・プレースホルダのいずれかとして返す（F01.2 設計書「祖先個別の返却フィルタ」参照）。</p>
     *
     * @param orgId       対象組織ID
     * @param requesterId 呼び出し者のユーザーID（未認証の場合 null）
     * @return 祖先一覧レスポンス
     * @throws BusinessException 対象組織が存在しない（ORG_001）／PRIVATE で未認証（COMMON_000）／
     *                           PRIVATE で外部ユーザー（COMMON_002）
     */
    public AncestorsResponse getAncestors(Long orgId, Long requesterId) {
        OrganizationEntity target = findOrganizationOrThrow(orgId);

        // 対象組織自体のアクセス可否を判定（PRIVATE の場合のみ厳格にチェック）
        boolean isDirectMember = requesterId != null
                && userRoleRepository.existsByUserIdAndOrganizationId(requesterId, orgId);
        boolean isDescendantMember = requesterId != null
                && !isDirectMember
                && isDescendantMember(requesterId, orgId);

        if (target.getVisibility() == OrganizationEntity.Visibility.PRIVATE) {
            if (requesterId == null) {
                throw new BusinessException(CommonErrorCode.COMMON_000);
            }
            if (!isDirectMember && !isDescendantMember) {
                throw new BusinessException(CommonErrorCode.COMMON_002);
            }
        }

        // 祖先チェーンを root → 親 の順に積む
        List<AncestorOrganizationResponse> chainRootFirst = buildAncestorChain(target, requesterId);
        boolean truncated = isAncestorChainTruncated(target);

        AncestorsResponse.AncestorsMeta meta = new AncestorsResponse.AncestorsMeta(
                chainRootFirst.size(), truncated);
        return new AncestorsResponse(chainRootFirst, meta);
    }

    /**
     * 子組織一覧カーソルページングで「所属組織 0 件」の呼び出し者に渡すセンチネル ID。
     *
     * <p>JPQL の {@code IN :collection} は空コレクションだと構文エラーになるため、
     * 所属組織が 0 件の場合はこの値のみを含む 1 要素リストを渡す。実在しない ID
     * （組織 ID は 1 始まりの正の値のみ発行される）なので、可視性条件の
     * {@code o.id IN :memberOrgIds} には絶対にマッチしない。PUBLIC 判定は
     * この条件と OR で独立しているため、所属 0 件でも PUBLIC な子は正しく見える。</p>
     */
    private static final Long NO_MEMBERSHIP_SENTINEL_ORG_ID = -1L;

    /**
     * 対象組織の直近の子組織一覧を返す。
     *
     * <p><b>根治した3つの欠陥（設計書なし・実測ベースの障害対応）</b>:</p>
     * <ul>
     *   <li><b>①カーソルが SQL に降りていない</b>: 旧実装は {@code PageRequest.of(0, n)} で
     *       常に先頭ページを取得し、カーソル条件をメモリ上でフィルタしていた。DB は
     *       毎回同じ先頭 {@code pageSize+1} 件を返すため、2ページ目以降が実質空になっていた。
     *       {@link OrganizationRepository#findChildrenPage} でカーソルを SQL の
     *       {@code WHERE o.id > :cursorId} へ降ろして根治した。</li>
     *   <li><b>② ORDER BY が無い</b>: unsorted な {@code Pageable} を使っており、ID 昇順を
     *       前提とするカーソルの順序保証が無かった。{@code findChildrenPage} に
     *       {@code ORDER BY o.id ASC} を明示して根治した。</li>
     *   <li><b>③ hasNext が可視性フィルタ後件数で判定されていた</b>: 非公開の子が1件混じるだけで
     *       {@code visible.size()} が {@code pageSize+1} に届かず、DB にまだ続きがあるのに
     *       {@code hasNext=false} になる偽陰性があった。可視性条件自体を SQL へ降ろし
     *       （{@code findChildrenPage} の {@code visibility = PUBLIC OR o.id IN :memberOrgIds}）、
     *       {@code hasNext} は「DB から {@code pageSize+1} 件返ってきたか」だけで判定するよう
     *       改めた。</li>
     * </ul>
     *
     * @param orgId       対象組織ID
     * @param requesterId 呼び出し者のユーザーID
     * @param cursor      ページネーションカーソル（次ページ用 ID）。最初のページは null。
     * @param size        ページサイズ。null/0以下/上限超は補正される。
     * @return 子組織一覧レスポンス
     * @throws BusinessException 対象組織が存在しない（ORG_001）／PRIVATE で非メンバー（COMMON_002）
     */
    public ChildrenResponse getChildren(Long orgId, Long requesterId, String cursor, int size) {
        if (requesterId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        OrganizationEntity target = findOrganizationOrThrow(orgId);

        // PRIVATE 組織は直接所属メンバーのみ閲覧可能
        if (target.getVisibility() == OrganizationEntity.Visibility.PRIVATE
                && !userRoleRepository.existsByUserIdAndOrganizationId(requesterId, orgId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        int pageSize = normalizeChildrenPageSize(size);
        // ID 昇順での簡易カーソル（cursor が数値 ID なら、それより大きい ID を取得）
        Long cursorId = parseCursor(cursor);
        Pageable pageable = PageRequest.of(0, pageSize + 1); // 次ページ判定用に +1 件取得

        // 可視性を SQL へ降ろすため、呼び出し者が直接所属する組織 ID 集合を事前取得する。
        // 空コレクションは JPQL の IN () で構文エラーになるためセンチネルへ差し替える。
        List<Long> memberOrgIds = userRoleRepository.findOrganizationIdsByUserId(requesterId);
        List<Long> memberOrgIdsForQuery = memberOrgIds.isEmpty()
                ? List.of(NO_MEMBERSHIP_SENTINEL_ORG_ID)
                : memberOrgIds;

        // カーソル・可視性・ID 昇順のすべてを SQL 側で解決した結果を取得する
        List<OrganizationEntity> rows = organizationRepository
                .findChildrenPage(orgId, cursorId, memberOrgIdsForQuery, pageable);

        // hasNext は「DB から pageSize+1 件返ってきたか」で判定する（③の根治）
        boolean hasNext = rows.size() > pageSize;
        List<OrganizationEntity> page = hasNext ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasNext && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).getId())
                : null;

        List<ChildOrganizationResponse> data = page.stream()
                .map(child -> ChildOrganizationResponse.builder()
                        .id(child.getId())
                        .slug(child.getSlug())
                        .name(child.getName())
                        .nickname1(child.getNickname1())
                        // 画像 URL 根治 Phase 2: 生 R2 キーを署名付き表示 URL へ解決
                        .iconUrl(mediaUrlResolver.resolve(child.getIconUrl()))
                        .visibility(child.getVisibility().name())
                        .memberCount((int) userRoleRepository.countByOrganizationId(child.getId()))
                        .archived(child.getArchivedAt() != null)
                        .build())
                .toList();

        CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(nextCursor, hasNext, pageSize);
        return new ChildrenResponse(data, meta);
    }

    /**
     * 祖先チェーンを root を先頭にした List で構築する。
     *
     * <p>{@code target.parentOrganizationId} を起点に最大 {@code maxDepth} 件まで親方向へ辿る。
     * サイクル（同一IDの再訪）を検出した時点で打ち切り。</p>
     */
    private List<AncestorOrganizationResponse> buildAncestorChain(OrganizationEntity target, Long requesterId) {
        // 直近の親 → さらに上 の順に積み、最後に逆順にする
        List<OrganizationEntity> ancestorsParentFirst = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        visited.add(target.getId());

        Long currentParentId = target.getParentOrganizationId();
        int hops = 0;
        while (currentParentId != null && hops < maxDepth) {
            if (!visited.add(currentParentId)) {
                // サイクル検出 → 打ち切り
                log.warn("組織階層にサイクルを検出: orgId={}, cycleAt={}", target.getId(), currentParentId);
                break;
            }
            OrganizationEntity ancestor = organizationRepository.findById(currentParentId).orElse(null);
            if (ancestor == null) {
                // 親IDが指し示す組織が存在しない（論理削除済み等）→ 打ち切り
                break;
            }
            ancestorsParentFirst.add(ancestor);
            currentParentId = ancestor.getParentOrganizationId();
            hops++;
        }

        // 各祖先を返却フィルタに通す（root を先頭にする）
        Collections.reverse(ancestorsParentFirst);
        List<AncestorOrganizationResponse> result = new ArrayList<>();
        for (OrganizationEntity ancestor : ancestorsParentFirst) {
            result.add(filterAncestor(ancestor, requesterId));
        }
        return result;
    }

    /**
     * {@code maxDepth} 到達による打ち切りが発生したかを判定する。
     *
     * <p>探索後にも {@code parent_organization_id} が残っている場合 {@code true}。</p>
     */
    private boolean isAncestorChainTruncated(OrganizationEntity target) {
        Set<Long> visited = new HashSet<>();
        visited.add(target.getId());
        Long currentParentId = target.getParentOrganizationId();
        int hops = 0;
        while (currentParentId != null && hops < maxDepth) {
            if (!visited.add(currentParentId)) {
                return false; // サイクル検出（truncated とは別概念）
            }
            OrganizationEntity ancestor = organizationRepository.findById(currentParentId).orElse(null);
            if (ancestor == null) {
                return false;
            }
            currentParentId = ancestor.getParentOrganizationId();
            hops++;
        }
        // hops == maxDepth に到達したのにまだ親が残っているなら truncated
        return currentParentId != null;
    }

    /**
     * 祖先1件を「直接所属／子孫メンバー＋hierarchyVisibility／外部ユーザー＋visibility」で
     * フル / 限定 / プレースホルダのいずれかに変換する。
     */
    private AncestorOrganizationResponse filterAncestor(OrganizationEntity ancestor, Long requesterId) {
        // 直接所属メンバー → フル情報
        if (requesterId != null
                && userRoleRepository.existsByUserIdAndOrganizationId(requesterId, ancestor.getId())) {
            return fullAncestor(ancestor);
        }

        // 子孫メンバー判定
        boolean isDescendant = requesterId != null && isDescendantMember(requesterId, ancestor.getId());
        if (isDescendant) {
            OrganizationEntity.HierarchyVisibility hv = ancestor.getHierarchyVisibility();
            if (hv == OrganizationEntity.HierarchyVisibility.FULL) {
                return fullAncestor(ancestor);
            }
            if (hv == OrganizationEntity.HierarchyVisibility.BASIC) {
                return basicAncestor(ancestor);
            }
            // NONE → プレースホルダ
            return hiddenAncestor(ancestor.getId());
        }

        // 外部ユーザー
        if (ancestor.getVisibility() == OrganizationEntity.Visibility.PUBLIC) {
            return publicLimitedAncestor(ancestor);
        }
        return hiddenAncestor(ancestor.getId());
    }

    /**
     * 呼び出し者が {@code targetOrgId} の子孫（子組織または所属チームのメンバー）かを判定する。
     *
     * <p>ユーザーが所属する全組織・全チームを取得し、それぞれの祖先チェーン（{@code maxDepth} まで）に
     * {@code targetOrgId} が含まれるかをチェックする。</p>
     */
    private boolean isDescendantMember(Long requesterId, Long targetOrgId) {
        // ユーザー所属組織のうち、祖先に targetOrgId を含むものがあれば true
        // CMP-027: user_roles ∪ memberships の在籍組織（素メンバー/応援者を取りこぼさない）
        for (Long memberOrgId : userRoleRepository.findOrganizationIdsByUserId(requesterId)) {
            if (memberOrgId == null) continue;
            if (memberOrgId.equals(targetOrgId)) continue; // 直接所属は別判定なので除外
            if (hasAncestor(memberOrgId, targetOrgId)) return true;
        }

        // ユーザー所属チームの所属組織を起点に祖先を辿る
        // CMP-027: user_roles ∪ memberships の在籍チーム
        for (Long teamId : userRoleRepository.findTeamIdsByUserId(requesterId)) {
            if (teamId == null) continue;
            List<TeamOrgMembershipEntity> memberships = teamOrgMembershipRepository
                    .findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE);
            for (TeamOrgMembershipEntity m : memberships) {
                Long anchorOrgId = m.getOrganizationId();
                if (anchorOrgId == null) continue;
                if (anchorOrgId.equals(targetOrgId)) return true;
                if (hasAncestor(anchorOrgId, targetOrgId)) return true;
            }
        }
        return false;
    }

    /**
     * {@code startOrgId} の祖先チェーン（最大 {@code maxDepth}）に {@code targetOrgId} が含まれるか判定する。
     */
    private boolean hasAncestor(Long startOrgId, Long targetOrgId) {
        Set<Long> visited = new HashSet<>();
        visited.add(startOrgId);
        Long current = organizationRepository.findParentOrganizationIdById(startOrgId).orElse(null);
        int hops = 0;
        while (current != null && hops < maxDepth) {
            if (current.equals(targetOrgId)) return true;
            if (!visited.add(current)) return false; // サイクル
            current = organizationRepository.findParentOrganizationIdById(current).orElse(null);
            hops++;
        }
        return false;
    }

    private AncestorOrganizationResponse fullAncestor(OrganizationEntity org) {
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .slug(org.getSlug())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .description(null) // organizations.description は現状未保持。philosophy 等は別 API で取得
                .iconUrl(mediaUrlResolver.resolve(org.getIconUrl()))
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse basicAncestor(OrganizationEntity org) {
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .slug(org.getSlug())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .description(null)
                .iconUrl(mediaUrlResolver.resolve(org.getIconUrl()))
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse publicLimitedAncestor(OrganizationEntity org) {
        // 外部ユーザー + PUBLIC: id / name / nickname1 / iconUrl / visibility のみ（description は外す）
        return AncestorOrganizationResponse.builder()
                .id(org.getId())
                .slug(org.getSlug())
                .name(org.getName())
                .nickname1(org.getNickname1())
                .iconUrl(mediaUrlResolver.resolve(org.getIconUrl()))
                .visibility(org.getVisibility().name())
                .hidden(false)
                .build();
    }

    private AncestorOrganizationResponse hiddenAncestor(Long id) {
        // hidden=true の場合、id 以外のフィールドは null（@JsonInclude(NON_NULL) で省略される）
        return AncestorOrganizationResponse.builder()
                .id(id)
                .hidden(true)
                .build();
    }

    private int normalizeChildrenPageSize(int size) {
        if (size <= 0) return CHILDREN_DEFAULT_PAGE_SIZE;
        if (size > CHILDREN_MAX_PAGE_SIZE) return CHILDREN_MAX_PAGE_SIZE;
        return size;
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private OrganizationEntity findOrganizationOrThrow(Long orgId) {
        return organizationRepository.findById(orgId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
    }
}
