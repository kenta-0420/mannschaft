package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先プレビュー・件数見込み（軍議第8版確定稿 §3.3・AC-20・AC-35）。
 *
 * <p>{@code POST .../confirmable-notifications/recipient-preview} の実処理。
 * 見込みが0件の場合、送信APIは {@code RECIPIENTS_EMPTY}（409）を返す（AC-20）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableRecipientPreviewService {

    /** OrgFanoutRecipientSource と同じサイクル防止上限。 */
    static final int MAX_ORG_DESCENDANT_DEPTH = 32;

    /** IN 句を空にできない（MySQL は {@code IN ()} を許さない）ためのプレースホルダID。 */
    private static final List<Long> NONE = List.of(-1L);

    private final ConfirmableNotificationTargetRepository targetRepository;
    private final ConfirmableRecipientGroupService recipientGroupService;

    public ConfirmableRecipientPreviewResponse preview(
            ScopeType scopeType, Long scopeId, Long requesterUserId, ConfirmableRecipientPreviewRequest request) {
        List<ConfirmableTargetSpec> targets;
        if (request.getRecipientGroupId() != null) {
            targets = recipientGroupService.resolveForSend(scopeType, scopeId, request.getRecipientGroupId());
        } else if (request.getTargets() != null && !request.getTargets().isEmpty()) {
            targets = request.getTargets();
        } else {
            // 既定の宛先（AC-1/AC-6）: 組織スコープは自組織、チームスコープは自チーム。
            targets = List.of(new ConfirmableTargetSpec(
                    scopeType == ScopeType.TEAM ? ConfirmableTargetType.TEAM : ConfirmableTargetType.ORGANIZATION,
                    scopeId));
        }

        List<Long> orgIds = targets.stream()
                .filter(t -> t.getType() == ConfirmableTargetType.ORGANIZATION)
                .map(ConfirmableTargetSpec::getId)
                .distinct()
                .toList();
        List<Long> teamIds = targets.stream()
                .filter(t -> t.getType() == ConfirmableTargetType.TEAM)
                .map(ConfirmableTargetSpec::getId)
                .distinct()
                .toList();

        long count = targetRepository.countAdHocTargetRecipients(
                orgIds.isEmpty() ? NONE : orgIds,
                teamIds.isEmpty() ? NONE : teamIds,
                false, MAX_ORG_DESCENDANT_DEPTH, requesterUserId);

        return ConfirmableRecipientPreviewResponse.builder().estimatedRecipientCount(count).build();
    }
}
