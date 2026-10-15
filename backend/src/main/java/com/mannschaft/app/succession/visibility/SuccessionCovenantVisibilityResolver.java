package com.mannschaft.app.succession.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.succession.repository.SuccessionCovenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F00 SUCCESSION_COVENANTS の可視性判定 Resolver（F09.15 S1 第三陣B）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §3.4（F00-A 並列カラム案）。
 * {@code corkboard_cards.reference_id_uuid} (BINARY(16)) 経路を使うため、
 * {@link ContentVisibilityResolver#canViewUuid(UUID, Long)} 系をオーバーライドする。
 *
 * <p>可視性ロジック:
 * <ul>
 *   <li>本人（{@code signer_user_id == viewerUserId}）→ 可視</li>
 *   <li>同一組織の ADMIN / DEPUTY_ADMIN → 可視</li>
 *   <li>その他 → 不可視</li>
 * </ul>
 *
 * <p>注意: {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * は Long 主キー前提のため本 Resolver では使わず、{@link ContentVisibilityResolver} を直接
 * 実装する。Long 経路（{@code canView(Long, Long)} 等）は呼ばれない想定で
 * {@link UnsupportedOperationException} のデフォルト動作に任せる代わりに、
 * fail-closed の {@code false} / 空集合を返すように明示オーバーライドする
 * （SUCCESSION_COVENANTS は UUID_V7 経路専用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuccessionCovenantVisibilityResolver
        implements ContentVisibilityResolver<Enum<?>> {

    private final SuccessionCovenantRepository covenantRepository;
    private final AccessControlService accessControlService;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SUCCESSION_COVENANTS;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        // SUCCESSION_COVENANTS は UUIDv7 経路専用。Long 経路は呼ばれない想定。
        log.warn("SUCCESSION_COVENANTS resolver called via Long path (should be UUID): contentId={}",
                contentId);
        return false;
    }

    @Override
    public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        // 同上。fail-closed で空集合を返す。
        return Collections.emptySet();
    }

    // ─── UUID 経路（本流） ───────────────────────────────────────

    @Override
    public boolean canViewUuid(UUID contentId, Long viewerUserId) {
        if (contentId == null || viewerUserId == null) {
            return false;
        }
        Optional<SuccessionCovenantEntity> opt = covenantRepository.findById(contentId);
        if (opt.isEmpty()) {
            // NOT_FOUND は fail-closed
            return false;
        }
        SuccessionCovenantEntity entity = opt.get();

        // 削除済みは誰も不可視
        if (entity.getDeletedAt() != null) {
            return false;
        }

        return isViewerAuthorized(entity, viewerUserId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        // バッチ取得：findAllById を使い 1 SQL で取得
        List<SuccessionCovenantEntity> rows = covenantRepository.findAllById(contentIds);
        Set<UUID> accessible = new HashSet<>();
        for (SuccessionCovenantEntity entity : rows) {
            if (entity == null || entity.getId() == null) {
                continue;
            }
            if (entity.getDeletedAt() != null) {
                continue;
            }
            if (isViewerAuthorized(entity, viewerUserId)) {
                accessible.add(entity.getId());
            }
        }
        return accessible;
    }

    private boolean isViewerAuthorized(SuccessionCovenantEntity entity, Long viewerUserId) {
        // 本人なら可視
        if (entity.getSignerUserId() != null && entity.getSignerUserId().equals(viewerUserId)) {
            return true;
        }
        // 同一組織の ADMIN/DEPUTY_ADMIN なら可視
        Long organizationId = entity.getOrganizationId();
        if (organizationId == null) {
            return false;
        }
        return accessControlService.isAdminOrAbove(viewerUserId, organizationId, "ORGANIZATION");
    }
}
