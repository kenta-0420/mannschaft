package com.mannschaft.app.succession.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.repository.SuccessionPreRegistrationRepository;
import com.mannschaft.app.succession.repository.UnsealRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F00 SUCCESSION_PRE_REGISTRATION の可視性判定 Resolver（F09.15 S2-B）。
 *
 * <p>設計書: F00-A 案（reference_id_uuid 並列カラム）を採用。
 * seal_status に基づいてアクセス制御する:
 * <ul>
 *   <li>SEALED / RE_SEALED: ADMIN のみ</li>
 *   <li>UNSEAL_REQUESTED: 申請者・一次承認者・ADMIN のみ</li>
 *   <li>UNSEALED（TTL 内）: 申請者・一次承認者・二次承認者・ADMIN のみ</li>
 *   <li>UNSEALED（TTL 超過）: fail-closed（RE_SEALED 扱い）</li>
 * </ul>
 *
 * <p>注意: {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * は Long 主キー前提のため本 Resolver では使わず、{@link ContentVisibilityResolver} を直接
 * 実装する。Long 経路（{@code canView(Long, Long)} 等）は呼ばれない想定で
 * fail-closed の {@code false} / 空集合を返すように明示オーバーライドする
 * （SUCCESSION_PRE_REGISTRATION は UUIDv7 経路専用）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuccessionPreRegistrationVisibilityResolver
        implements ContentVisibilityResolver<Enum<?>> {

    private final SuccessionPreRegistrationRepository preRegRepo;
    private final UnsealRequestRepository unsealRequestRepo;
    private final AccessControlService accessControlService;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.SUCCESSION_PRE_REGISTRATION;
    }

    // ─── Long 経路（未使用・fail-closed） ─────────────────────────

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        // SUCCESSION_PRE_REGISTRATION は UUIDv7 経路専用。Long 経路は呼ばれない想定。
        log.warn("SUCCESSION_PRE_REGISTRATION resolver called via Long path (should be UUID): contentId={}",
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
        Optional<SuccessionPreRegistrationEntity> opt = preRegRepo.findById(contentId);
        if (opt.isEmpty()) {
            // NOT_FOUND は fail-closed
            return false;
        }
        SuccessionPreRegistrationEntity preReg = opt.get();

        // 削除済みは誰も不可視
        if (preReg.getDeletedAt() != null) {
            return false;
        }

        return isAuthorized(preReg, viewerUserId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        // バッチ取得：findAllById を使い 1 SQL で取得
        List<SuccessionPreRegistrationEntity> rows = preRegRepo.findAllById(contentIds);
        Set<UUID> accessible = new HashSet<>();
        for (SuccessionPreRegistrationEntity preReg : rows) {
            if (preReg == null || preReg.getId() == null) {
                continue;
            }
            if (preReg.getDeletedAt() != null) {
                continue;
            }
            if (isAuthorized(preReg, viewerUserId)) {
                accessible.add(preReg.getId());
            }
        }
        return accessible;
    }

    private boolean isAuthorized(SuccessionPreRegistrationEntity preReg, Long viewerUserId) {
        Long orgId = preReg.getOrganizationId();

        // ADMIN は常に可視
        if (orgId != null && accessControlService.isAdminOrAbove(viewerUserId, orgId, "ORGANIZATION")) {
            return true;
        }

        String sealStatus = preReg.getSealStatus();
        if (sealStatus == null) {
            return false;
        }

        return switch (sealStatus) {
            case "SEALED", "RE_SEALED" ->
                // ADMIN のみ（上記で対処済み）
                false;

            case "UNSEAL_REQUESTED", "UNSEALED" -> {
                // TTL チェック（UNSEALED かつ期限切れ → false）
                if ("UNSEALED".equals(sealStatus)) {
                    LocalDateTime resealAt = preReg.getAutoResealAt();
                    if (resealAt == null || LocalDateTime.now().isAfter(resealAt)) {
                        yield false;
                    }
                }
                // 申請者・承認者集合に含まれるか
                yield isInApproverSet(preReg.getId(), viewerUserId);
            }

            default -> false;
        };
    }

    /**
     * 直近の有効な封緘解除申請における申請者・承認者集合に {@code viewerUserId} が含まれるか確認する。
     *
     * <ul>
     *   <li>UNSEAL_REQUESTED 状態: 申請者・一次承認者が対象</li>
     *   <li>UNSEALED 状態: 申請者・一次承認者・二次承認者が対象（unsealCompletedAt あり）</li>
     * </ul>
     */
    private boolean isInApproverSet(UUID preRegistrationId, Long viewerUserId) {
        List<UnsealRequestEntity> requests = unsealRequestRepo
                .findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(preRegistrationId);
        for (UnsealRequestEntity req : requests) {
            // 否決済みはスキップ
            if (req.getRejectedAt() != null) {
                continue;
            }
            if (viewerUserId.equals(req.getRequestedBy())) {
                return true;
            }
            if (viewerUserId.equals(req.getFirstApproverUserId())) {
                return true;
            }
            // 二次承認者は UNSEALED 完了後（unsealCompletedAt あり）かつ未再封の場合のみ
            if (req.getUnsealCompletedAt() != null
                    && viewerUserId.equals(req.getSecondApproverUserId())) {
                return true;
            }
            // 直近の有効申請のみ確認（否決済みをスキップした後の最初の有効申請）
            break;
        }
        return false;
    }
}
