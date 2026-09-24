package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientGroupResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
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

    /**
     * 送信時、指定グループを送信スコープに対して解決しターゲット一覧を返す（軍議第8版確定稿 §3.3・AC-15・AC-7）。
     *
     * <p>存在しない・論理削除済み・他スコープのグループはすべて {@code RECIPIENT_GROUP_NOT_FOUND}
     * （404・存在秘匿）とする（AC-15）。認可済みのターゲット一覧は送信の時点で展開する（AC-7）。</p>
     */
    public List<ConfirmableTargetSpec> resolveForSend(ScopeType scopeType, Long scopeId, UUID groupId) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
