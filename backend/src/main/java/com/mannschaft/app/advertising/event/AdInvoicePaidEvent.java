package com.mannschaft.app.advertising.event;

/**
 * 広告費請求書の入金が確定したことを表すドメインイベント（F08.12 §5.2）。
 *
 * <p>ドメイン境界を越えるため<b>ID 参照だけを載せる</b>（設計原則 1・5）。
 * 受け手は {@code source_type} + {@code source_ref} で元データを引き直す。</p>
 *
 * @param adInvoiceId 入金確定した広告費請求書の ID
 */
public record AdInvoicePaidEvent(Long adInvoiceId) {
}
