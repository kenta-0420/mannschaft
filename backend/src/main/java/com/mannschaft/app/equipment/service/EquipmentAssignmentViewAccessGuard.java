package com.mannschaft.app.equipment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.equipment.EquipmentErrorCode;
import com.mannschaft.app.equipment.entity.EquipmentAssignmentEntity;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.equipment.repository.EquipmentAssignmentRepository;
import com.mannschaft.app.equipment.repository.EquipmentItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

/**
 * 備品貸出記録の返却（単件・一括）前に通す「閲覧可否の門」（CMP-260821-1027 存在オラクルの本文正規化）。
 *
 * <h2>保証する内容</h2>
 * <p>パスの備品を閲覧できる者（貸出履歴 {@code getHistoryFor*} と同じ、備品のスコープの所属者）について、
 * 指定した貸出記録がその備品の貸出として見えないもの（不在・他備品・他チーム/他組織の備品の貸出）であれば、
 * 一律に不在と同一の {@link EquipmentErrorCode#ASSIGNMENT_NOT_FOUND}（EQUIPMENT_002・404・同一本文）を返す。
 * 一括返却は、返却済みなどの状態診断より先に全件を検証するため、指定順で応答が割れない。</p>
 *
 * <p>パスの備品が無い者・備品のスコープに所属しない者は、貸出記録 ID に関わらず同じ応答になるよう
 * 何もせずに通す（備品の不在 404 と ADMIN 未満の 403 は {@link EquipmentAssignmentService} が貸出記録を見る前に返す）。
 * 門で先に貸出記録を検証すると、所属しない者に貸出記録の実在が漏れるためである。</p>
 *
 * <p>取引を持たない部品として Controller から Service より先に呼ぶ（{@code CorkboardScopedBoardVisibilityGuard} と
 * 同じ型）。{@link EquipmentAssignmentService} 側にも同じ所属検証が残る（二重防御）。</p>
 */
@Component
@RequiredArgsConstructor
public class EquipmentAssignmentViewAccessGuard {

    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final EquipmentItemRepository itemRepository;
    private final EquipmentAssignmentRepository assignmentRepository;
    private final AccessControlService accessControlService;

    /**
     * チーム備品の返却対象の貸出記録が、パスの備品の貸出として見えることを確かめる。
     *
     * @param teamId        チームID（パス由来）
     * @param itemId        備品ID（パス由来）
     * @param assignmentIds 返却する貸出記録ID
     * @param userId        操作ユーザーID
     * @throws BusinessException 見えない貸出記録を含む場合（{@link EquipmentErrorCode#ASSIGNMENT_NOT_FOUND} / 404）
     */
    public void requireTeamItemAssignments(Long teamId, Long itemId, Collection<Long> assignmentIds, Long userId) {
        requireAssignments(itemRepository.findByIdAndTeamId(itemId, teamId), SCOPE_TEAM, assignmentIds, userId);
    }

    /**
     * 組織備品の返却対象の貸出記録が、パスの備品の貸出として見えることを確かめる。
     *
     * @param orgId         組織ID（パス由来）
     * @param itemId        備品ID（パス由来）
     * @param assignmentIds 返却する貸出記録ID
     * @param userId        操作ユーザーID
     * @throws BusinessException 見えない貸出記録を含む場合（{@link EquipmentErrorCode#ASSIGNMENT_NOT_FOUND} / 404）
     */
    public void requireOrganizationItemAssignments(Long orgId, Long itemId, Collection<Long> assignmentIds,
                                                   Long userId) {
        requireAssignments(itemRepository.findByIdAndOrganizationId(itemId, orgId), SCOPE_ORGANIZATION,
                assignmentIds, userId);
    }

    private void requireAssignments(Optional<EquipmentItemEntity> itemOpt, String scopeType,
                                    Collection<Long> assignmentIds, Long userId) {
        if (itemOpt.isEmpty() || assignmentIds == null) {
            return;
        }
        EquipmentItemEntity item = itemOpt.get();
        Long scopeId = SCOPE_TEAM.equals(scopeType) ? item.getTeamId() : item.getOrganizationId();
        // 貸出履歴と同じ閲覧資格（備品のスコープの所属者）。所属しない者は貸出記録を見ずに通す。
        if (!accessControlService.isMember(userId, scopeId, scopeType)) {
            return;
        }
        for (Long assignmentId : assignmentIds) {
            boolean ofThisItem = assignmentId != null && assignmentRepository.findById(assignmentId)
                    .map(EquipmentAssignmentEntity::getEquipmentItemId)
                    .filter(item.getId()::equals)
                    .isPresent();
            if (!ofThisItem) {
                throw new BusinessException(EquipmentErrorCode.ASSIGNMENT_NOT_FOUND);
            }
        }
    }
}
