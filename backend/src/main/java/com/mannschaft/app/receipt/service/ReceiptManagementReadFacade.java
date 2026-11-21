package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.receipt.ReceiptArchiveKind;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.dto.ReceiptResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理用領収書詳細/PDFの認可入口。認可を業務TXの外で行うためTransactionalを付けない。
 * 自ドメインでIDと入力scopeを照合→既存ACL→業務TXで再取得の順とし、
 * 不一致・不在の404と非所属の403を維持する。本人用ReceiptMyServiceには介入しない。
 */
@Service
@RequiredArgsConstructor
public class ReceiptManagementReadFacade {

    private final ReceiptService receiptService;
    private final AccessControlService accessControlService;

    /** 組織管理者、または従来どおりTEAM会員へ領収書詳細を返す。 */
    public ReceiptResponse getReceipt(ReceiptScopeType scopeType, Long scopeId, Long receiptId, Long actorUserId) {
        requireReadAccess(scopeType, scopeId, receiptId, actorUserId);
        return receiptService.getReceipt(scopeType, scopeId, receiptId);
    }

    /** 認可後にPDFの業務TXへ入り、保存済み原本または生成した原本を返す。 */
    public byte[] getReceiptPdf(ReceiptScopeType scopeType, Long scopeId, Long receiptId, Long actorUserId,
                               ReceiptArchiveKind kind) {
        requireReadAccess(scopeType, scopeId, receiptId, actorUserId);
        return receiptService.getReceiptPdf(scopeType, scopeId, receiptId, kind);
    }

    private void requireReadAccess(ReceiptScopeType scopeType, Long scopeId, Long receiptId, Long actorUserId) {
        ReceiptService.ReceiptReadScope scope = receiptService.resolveManagementReadScope(scopeType, scopeId, receiptId);
        if (scope.scopeType() == ReceiptScopeType.ORGANIZATION) {
            accessControlService.checkAdminOrAbove(actorUserId, scope.scopeId(), scope.scopeType().name());
        } else {
            accessControlService.checkMembership(actorUserId, scope.scopeId(), scope.scopeType().name());
        }
    }
}
