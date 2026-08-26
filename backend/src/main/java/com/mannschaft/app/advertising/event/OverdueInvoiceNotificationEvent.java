package com.mannschaft.app.advertising.event;

import java.time.LocalDate;
import java.util.List;

/**
 * 広告請求書 OVERDUE 遷移の通知配送要求イベント（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>{@code OverdueInvoiceMarkRunner#markOne} が 1 請求書ぶんの状態更新
 * （{@code ISSUED → OVERDUE}）を独立トランザクションでコミットする直前に publish し、
 * {@link OverdueInvoiceNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>受信者をイベントに載せる理由</h2>
 * <p>受信者の解決は {@code user_roles} を引く越境参照であり、advertising ドメイン側の Runner が
 * 業務トランザクション内で 1 回だけ行う。配送リスナー側で解決すると、リスナーが role ドメインの
 * Repository を直接 DI することになりアーキテクチャ番人（D-5 / D-1）に触れるうえ、
 * 受信者解決の失敗が「通知が 1 件も出ない」形で配送層に混ざる。</p>
 *
 * @param invoiceId           請求書ID（通知の source。OVERDUE 遷移では行は削除されないため生存している）
 * @param invoiceNumber       請求書番号（本文組み立て用）
 * @param dueDate             支払期限（本文組み立て用）
 * @param organizationId      広告主スコープID（{@code advertiser_accounts.scope_id}）。解決できない場合は {@code null}
 * @param organizationAdmins  広告主組織の ADMIN 受信者
 * @param systemAdminUserIds  SYSTEM_ADMIN 受信者のユーザーID
 */
public record OverdueInvoiceNotificationEvent(
        Long invoiceId,
        String invoiceNumber,
        LocalDate dueDate,
        Long organizationId,
        List<Recipient> organizationAdmins,
        List<Long> systemAdminUserIds) {

    /**
     * 広告主組織 ADMIN の受信者。
     *
     * @param userId ユーザーID
     * @param email  メールアドレス（{@code null} / 空ならメール送信をスキップする）
     */
    public record Recipient(Long userId, String email) {
    }
}
