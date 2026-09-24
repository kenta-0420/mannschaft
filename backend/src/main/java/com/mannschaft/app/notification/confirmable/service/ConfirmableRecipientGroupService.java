package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupTargetEntity;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupTargetRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループ CRUD（軍議第8版確定稿 §3.1・AC-31）。
 *
 * <p>同じスコープで名前が重複すると {@code GROUP_NAME_DUPLICATE}（409）、ターゲットには
 * {@link ConfirmableTargetAuthorizationValidator} と同じ認可検証を掛ける（AC-12〜14 と同じ・AC-31）。</p>
 */
@Service
@Transactional(readOnly = true)
public class ConfirmableRecipientGroupService {

    private final ConfirmableRecipientGroupRepository groupRepository;
    private final ConfirmableRecipientGroupTargetRepository groupTargetRepository;
    private final ConfirmableTargetAuthorizationValidator authorizationValidator;
    /**
     * CI是正（CMP-260920-1040）: {@link ConfirmableRecipientGroupEntity#softDelete(Clock)} へ渡す
     * 壁時計クロック（引数なし {@code LocalDateTime.now()} は番人違反のため注入する）。
     */
    private final Clock clock;

    public ConfirmableRecipientGroupService(
            ConfirmableRecipientGroupRepository groupRepository,
            ConfirmableRecipientGroupTargetRepository groupTargetRepository,
            ConfirmableTargetAuthorizationValidator authorizationValidator,
            @Qualifier("wallClock") Clock clock) {
        this.groupRepository = groupRepository;
        this.groupTargetRepository = groupTargetRepository;
        this.authorizationValidator = authorizationValidator;
        this.clock = clock;
    }

    @Transactional
    public ConfirmableRecipientGroupResponse create(
            ScopeType scopeType, Long scopeId, Long createdByUserId, ConfirmableRecipientGroupCreateRequest request) {
        authorizationValidator.validateForGroupRegistration(scopeType, scopeId, request.getTargets());
        if (groupRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                scopeType, scopeId, request.getName())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.GROUP_NAME_DUPLICATE);
        }
        ConfirmableRecipientGroupEntity group = groupRepository.save(ConfirmableRecipientGroupEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .createdBy(createdByUserId)
                .build());
        saveTargets(group.getId(), request.getTargets());
        return toResponse(group, request.getTargets());
    }

    public List<ConfirmableRecipientGroupResponse> list(ScopeType scopeType, Long scopeId) {
        return groupRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNull(scopeType, scopeId).stream()
                .map(group -> toResponse(group, targetsOf(group.getId())))
                .collect(Collectors.toList());
    }

    @Transactional
    public ConfirmableRecipientGroupResponse update(
            ScopeType scopeType, Long scopeId, UUID groupId, ConfirmableRecipientGroupCreateRequest request) {
        ConfirmableRecipientGroupEntity group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .filter(g -> g.getScopeType() == scopeType && Objects.equals(g.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND));

        if (!group.getName().equals(request.getName())
                && groupRepository.existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
                        scopeType, scopeId, request.getName())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.GROUP_NAME_DUPLICATE);
        }
        authorizationValidator.validateForGroupRegistration(scopeType, scopeId, request.getTargets());

        group.rename(request.getName());
        groupTargetRepository.deleteByGroupId(groupId);
        saveTargets(groupId, request.getTargets());
        return toResponse(group, request.getTargets());
    }

    @Transactional
    public void delete(ScopeType scopeType, Long scopeId, UUID groupId) {
        ConfirmableRecipientGroupEntity group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .filter(g -> g.getScopeType() == scopeType && Objects.equals(g.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND));
        group.softDelete(clock);
    }

    /**
     * 送信時、指定グループを送信スコープに対して解決しターゲット一覧を返す（軍議第8版確定稿 §3.3・AC-15・AC-7）。
     *
     * <p>存在しない・論理削除済み・他スコープのグループはすべて {@code RECIPIENT_GROUP_NOT_FOUND}
     * （404・存在秘匿）とする（AC-15）。認可済みのターゲット一覧は送信の時点で展開する（AC-7）。</p>
     */
    public List<ConfirmableTargetSpec> resolveForSend(ScopeType scopeType, Long scopeId, UUID groupId) {
        ConfirmableRecipientGroupEntity group = groupRepository.findByIdAndDeletedAtIsNull(groupId)
                .filter(g -> g.getScopeType() == scopeType && Objects.equals(g.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.RECIPIENT_GROUP_NOT_FOUND));
        return targetsOf(group.getId());
    }

    private void saveTargets(UUID groupId, List<ConfirmableTargetSpec> targets) {
        List<ConfirmableRecipientGroupTargetEntity> entities = targets.stream()
                .map(t -> ConfirmableRecipientGroupTargetEntity.builder()
                        .groupId(groupId)
                        .targetType(t.getType())
                        .targetId(t.getId())
                        .build())
                .collect(Collectors.toList());
        groupTargetRepository.saveAll(entities);
    }

    private List<ConfirmableTargetSpec> targetsOf(UUID groupId) {
        return groupTargetRepository.findByGroupId(groupId).stream()
                .map(t -> new ConfirmableTargetSpec(t.getTargetType(), t.getTargetId()))
                .collect(Collectors.toList());
    }

    private ConfirmableRecipientGroupResponse toResponse(
            ConfirmableRecipientGroupEntity group, List<ConfirmableTargetSpec> targets) {
        return ConfirmableRecipientGroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .targets(targets)
                .createdAt(group.getCreatedAt())
                .build();
    }

}
