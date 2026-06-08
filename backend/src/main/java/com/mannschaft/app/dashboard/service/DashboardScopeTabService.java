package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.dashboard.DashboardScopeTabErrorCode;
import com.mannschaft.app.dashboard.dto.ScopeTabItemResponse;
import com.mannschaft.app.dashboard.dto.ScopeTabOrderUpdateRequest;
import com.mannschaft.app.dashboard.dto.ScopeTabPageResponse;
import com.mannschaft.app.dashboard.entity.DashboardScopeTabOrderEntity;
import com.mannschaft.app.dashboard.repository.DashboardScopeTabOrderRepository;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F22.1: 横スワイプ・ダッシュボードのチーム/組織タグ表示順サービス。
 *
 * <p>本サービスは「scope-tabs 基盤」（タグ一覧取得 + 表示順更新）のみを担う。
 * チーム/組織パネルのウィジェット拡張・統合「要対応」集計は別フェーズ（Wave 2）の担当。</p>
 *
 * <p>IDOR 防止: すべての操作は {@link SecurityUtils#getCurrentUserId()} で自分の ID を確定し、
 * リクエストに userId を露出しない（03_security_ux.md §1.2）。</p>
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.1 / §3.2</p>
 */
@Service
@RequiredArgsConstructor
public class DashboardScopeTabService {

    /** 1 ページの固定件数（要件 5: 上位 6 件ずつ表示）。 */
    private static final int PAGE_SIZE = 6;

    private final DashboardScopeTabOrderRepository scopeTabOrderRepository;
    private final MembershipRepository membershipRepository;
    private final MyScopeFolderRepository scopeFolderRepository;
    private final MyScopeFolderItemRepository scopeFolderItemRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    // ============================================
    // GET /dashboard/scope-tabs
    // ============================================

    /**
     * 表示順適用済みの所属スコープ（タグ）一覧を 6 件/ページで返す。
     *
     * <p>並び順ロジック（02 §3.1）:</p>
     * <ol>
     *   <li>保存済み行を sort_order 昇順で取得。</li>
     *   <li>未保存の所属スコープを末尾に補完（joined_at 降順 ≒ last_accessed_at 降順の近似）。</li>
     *   <li>folderId 指定時は、自分所有フォルダのみ・当該フォルダのアイテム scope_id に絞り込み（並び順適用の前）。</li>
     *   <li>現在の所属集合と突合し、退会/権限喪失スコープを除外。</li>
     *   <li>page * 6 から 6 件を切り出す。</li>
     * </ol>
     *
     * @param scopeTypeRaw スコープ種別（TEAM / ORGANIZATION）
     * @param page         0 始まりのページ番号（負値は 0 に丸める）
     * @param folderId     F15.3 フォルダ ID（null 可・自分所有のみ）
     */
    @Transactional(readOnly = true)
    public ScopeTabPageResponse getScopeTabs(String scopeTypeRaw, int page, Long folderId) {
        Long userId = SecurityUtils.getCurrentUserId();
        String scopeType = normalizeScopeType(scopeTypeRaw);
        int safePage = Math.max(0, page);

        // ① 現在の所属スコープ集合（真実の源）。退会/権限喪失スコープはここに含まれない。
        List<MembershipEntity> activeMemberships =
                membershipRepository.findActiveByUserAndScopeType(
                        userId, toMembershipScopeType(scopeType));

        // 所属 scope_id の登場順（joined_at 降順）を保持しつつ重複排除する。
        //   1 ユーザー × スコープに複数のアクティブ行が理屈上ありうる（再加入歴）ため LinkedHashSet で de-dup。
        Set<Long> activeScopeIds = new HashSet<>();
        List<Long> membershipOrder = new ArrayList<>();
        for (MembershipEntity m : activeMemberships) {
            if (activeScopeIds.add(m.getScopeId())) {
                membershipOrder.add(m.getScopeId());
            }
        }

        // ③ folderId 指定時の絞り込み対象集合（フィルタは並び順適用の前）。
        Set<Long> folderScopeIds = null;
        if (folderId != null) {
            folderScopeIds = resolveFolderScopeIds(userId, scopeType, folderId);
        }

        // ② 保存済み行（sort_order 昇順）→ 未保存所属（joined_at 降順）の順で並べる。
        List<DashboardScopeTabOrderEntity> saved =
                scopeTabOrderRepository.findByUserIdAndScopeTypeOrderBySortOrderAsc(userId, scopeType);

        // sort_order を保持するため、scopeId → savedSortOrder のマップを作る。
        Map<Long, Integer> savedSortOrder = new LinkedHashMap<>();
        for (DashboardScopeTabOrderEntity e : saved) {
            savedSortOrder.putIfAbsent(e.getScopeId(), e.getSortOrder());
        }

        // 並び順の最終リスト（scope_id）を構築:
        //   先頭 = 保存済み行の順（ただし④で現所属・③でフォルダにより除外）
        //   末尾 = 未保存の所属スコープ（membershipOrder の順）
        List<Long> orderedScopeIds = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        for (Long scopeId : savedSortOrder.keySet()) {
            if (isEligible(scopeId, activeScopeIds, folderScopeIds) && placed.add(scopeId)) {
                orderedScopeIds.add(scopeId);
            }
        }
        for (Long scopeId : membershipOrder) {
            if (isEligible(scopeId, activeScopeIds, folderScopeIds) && placed.add(scopeId)) {
                orderedScopeIds.add(scopeId);
            }
        }

        int totalCount = orderedScopeIds.size();
        int totalPages = (int) Math.ceil((double) totalCount / PAGE_SIZE);

        // ⑤ page * 6 から 6 件を切り出す（範囲外は空リスト）。
        int from = safePage * PAGE_SIZE;
        List<ScopeTabItemResponse> items = new ArrayList<>();
        if (from < totalCount) {
            int to = Math.min(from + PAGE_SIZE, totalCount);
            for (int i = from; i < to; i++) {
                Long scopeId = orderedScopeIds.get(i);
                // sort_order は保存済みなら保存値、未保存なら最終的な並び位置 i を用いる。
                int effectiveSortOrder = savedSortOrder.getOrDefault(scopeId, i);
                items.add(buildItem(scopeType, scopeId, effectiveSortOrder));
            }
        }

        return ScopeTabPageResponse.builder()
                .items(items)
                .page(safePage)
                .pageSize(PAGE_SIZE)
                .totalPages(totalPages)
                .totalCount(totalCount)
                .hasNext(from + PAGE_SIZE < totalCount)
                .hasPrev(safePage > 0 && totalCount > 0)
                .build();
    }

    /**
     * タグ 1 件のレスポンスを構築する。
     *
     * <p><b>unread_count の扱い</b>: タイムライン/掲示板/チャット + 要対応の総和集計は Wave 2 の担当。
     * 本波では集計源を持たないため <b>0 を正直に返す</b>（フェイク禁止・対処療法禁止）。
     * Wave 2 で action-required 込みの未読集計（Valkey キャッシュ）に拡張予定。</p>
     */
    private ScopeTabItemResponse buildItem(String scopeType, Long scopeId, int sortOrder) {
        String name;
        String avatarUrl;
        UUID publicId = null;
        if ("TEAM".equals(scopeType)) {
            TeamEntity team = teamRepository.findById(scopeId).orElse(null);
            name = team != null ? team.getName() : null;
            avatarUrl = team != null ? team.getIconUrl() : null;
            publicId = team != null ? team.getPublicId() : null;
        } else {
            OrganizationEntity org = organizationRepository.findById(scopeId).orElse(null);
            name = org != null ? org.getName() : null;
            avatarUrl = org != null ? org.getIconUrl() : null;
            publicId = org != null ? org.getPublicId() : null;
        }
        return ScopeTabItemResponse.builder()
                .scopeId(scopeId)
                .publicId(publicId)
                .scopeType(scopeType)
                .name(name)
                .avatarUrl(avatarUrl)
                // Wave 2 で action-required 込みの未読集計に拡張予定。現時点では集計源がないため 0。
                .unreadCount(0)
                .sortOrder(sortOrder)
                .build();
    }

    /**
     * folderId が自分所有であることを検証し、当該フォルダに割り当てられた scope_id 集合を返す。
     * 他人所有 / 不在のフォルダは 404（存在隠蔽）。
     */
    private Set<Long> resolveFolderScopeIds(Long userId, String scopeType, Long folderId) {
        MyScopeFolderEntity folder = scopeFolderRepository
                .findByIdAndUserIdAndDeletedAtIsNull(folderId, userId)
                .orElseThrow(() -> new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_004));
        // フォルダの scope_type とリクエストの scope_type が一致しなければ、対象なし扱い（空集合）。
        if (!folder.getScopeType().name().equals(scopeType)) {
            return Set.of();
        }
        Set<Long> scopeIds = new HashSet<>();
        for (MyScopeFolderItemEntity item : scopeFolderItemRepository.findByFolderIdOrderBySortOrder(folderId)) {
            scopeIds.add(item.getScopeId());
        }
        return scopeIds;
    }

    /**
     * scope_id が現在の所属集合に含まれ（④退会/権限喪失除外）、
     * かつ folderId 指定時は当該フォルダ対象集合に含まれる（③フィルタ）かを判定する。
     */
    private boolean isEligible(Long scopeId, Set<Long> activeScopeIds, Set<Long> folderScopeIds) {
        if (!activeScopeIds.contains(scopeId)) {
            return false;
        }
        return folderScopeIds == null || folderScopeIds.contains(scopeId);
    }

    // ============================================
    // PUT /dashboard/scope-tabs/order
    // ============================================

    /**
     * タグ表示順を一括更新（UPSERT）する。
     *
     * <p>所属検証: orders 全件を {@link AccessControlService#isMember} にかけ、
     * 1 件でも非所属なら全体を 403（SCOPE_TAB_001）で拒否（部分適用しない）。
     * sortOrder の重複・範囲外は SCOPE_TAB_002、scopeType 不正は SCOPE_TAB_003。</p>
     *
     * <p>@Transactional は dashboard ドメイン内に閉じる（所属検証のみ他ドメインの
     * AccessControlService を読み取りで呼ぶ。書き込みは scope_tab_order のみ）。</p>
     */
    @Transactional
    public void updateOrder(ScopeTabOrderUpdateRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        String scopeType = normalizeScopeType(req.getScopeType());

        List<ScopeTabOrderUpdateRequest.OrderItem> orders = req.getOrders();

        // sortOrder の重複検証（リクエスト内一意）。範囲（0-9999）は Bean Validation で済んでいる。
        Set<Integer> seenSortOrders = new HashSet<>();
        for (ScopeTabOrderUpdateRequest.OrderItem o : orders) {
            if (!seenSortOrders.add(o.getSortOrder())) {
                throw new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_002);
            }
        }

        // 所属検証（非所属が 1 件でも混入していれば全体拒否）。UPSERT より前に全件検証する。
        for (ScopeTabOrderUpdateRequest.OrderItem o : orders) {
            if (!accessControlService.isMember(userId, o.getScopeId(), scopeType)) {
                throw new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_001);
            }
        }

        // UPSERT（unique key: user_id, scope_type, scope_id）。
        //   既存行があれば sort_order を更新、なければ新規行を作成（UUIDv7 は @UuidGenerator が採番）。
        List<DashboardScopeTabOrderEntity> toSave = new ArrayList<>();
        for (ScopeTabOrderUpdateRequest.OrderItem o : orders) {
            DashboardScopeTabOrderEntity entity = scopeTabOrderRepository
                    .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, o.getScopeId())
                    .orElseGet(() -> DashboardScopeTabOrderEntity.builder()
                            .userId(userId)
                            .scopeType(scopeType)
                            .scopeId(o.getScopeId())
                            .build());
            entity.setSortOrder(o.getSortOrder());
            toSave.add(entity);
        }
        scopeTabOrderRepository.saveAll(toSave);

        // 監査ログ（成功後のみ。PII は含めず件数のみ記録する）。
        String metadata = String.format(
                "{\"source\":\"DASHBOARD_SCOPE_TAB\",\"scope_type\":\"%s\",\"order_count\":%d}",
                scopeType, orders.size());
        auditLogService.record(AuditEventType.DASHBOARD_SCOPE_TAB_ORDER_UPDATED.name(),
                userId, null, null, null, null, null,
                SecurityUtils.getCurrentSessionHash(), metadata);
    }

    // ============================================
    // ヘルパー
    // ============================================

    /**
     * scopeType を正規化し、TEAM / ORGANIZATION のみ許容する。
     * それ以外（PERSONAL 含む）は SCOPE_TAB_003。
     */
    private String normalizeScopeType(String raw) {
        if (raw == null) {
            throw new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_003);
        }
        String upper = raw.trim().toUpperCase();
        if (!"TEAM".equals(upper) && !"ORGANIZATION".equals(upper)) {
            throw new BusinessException(DashboardScopeTabErrorCode.SCOPE_TAB_003);
        }
        return upper;
    }

    /**
     * dashboard の scopeType 文字列を membership ドメインの ScopeType enum に変換する。
     */
    private com.mannschaft.app.membership.domain.ScopeType toMembershipScopeType(String scopeType) {
        return com.mannschaft.app.membership.domain.ScopeType.valueOf(scopeType);
    }
}
