package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableRecipientPreviewResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先プレビュー・件数見込み（軍議第8版確定稿 §3.3・AC-20・AC-35）。
 *
 * <p><b>骨格のみ（試練A）。出陣で実装する。</b>
 * {@code POST .../confirmable-notifications/recipient-preview} の実処理。
 * 見込みが0件の場合、送信APIは {@code RECIPIENTS_EMPTY}（409）を返す（AC-20）。</p>
 */
@Service
@Transactional(readOnly = true)
public class ConfirmableRecipientPreviewService {

    public ConfirmableRecipientPreviewResponse preview(
            ScopeType scopeType, Long scopeId, Long requesterUserId, ConfirmableRecipientPreviewRequest request) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
