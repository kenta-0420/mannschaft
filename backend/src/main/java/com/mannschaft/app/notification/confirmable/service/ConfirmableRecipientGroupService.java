package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループ CRUD（軍議第8版確定稿 §3.1・AC-31）。
 *
 * <p><b>骨格のみ（試練A）。出陣で実装する。</b> 同じスコープで名前が重複すると
 * {@code GROUP_NAME_DUPLICATE}（409）、ターゲットには {@link ConfirmableTargetAuthorizationValidator}
 * と同じ認可検証を掛ける（AC-12〜14 と同じ・AC-31）。</p>
 */
@Service
@Transactional(readOnly = true)
public class ConfirmableRecipientGroupService {

    @Transactional
    public ConfirmableRecipientGroupResponse create(
            ScopeType scopeType, Long scopeId, Long createdByUserId, ConfirmableRecipientGroupCreateRequest request) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    public List<ConfirmableRecipientGroupResponse> list(ScopeType scopeType, Long scopeId) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    @Transactional
    public ConfirmableRecipientGroupResponse update(
            ScopeType scopeType, Long scopeId, UUID groupId, ConfirmableRecipientGroupCreateRequest request) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    @Transactional
    public void delete(ScopeType scopeType, Long scopeId, UUID groupId) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
