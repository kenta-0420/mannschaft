package com.mannschaft.app.billing.api;

/** return state の検証失敗を詳細非公開で通知する契約。 */
final class BillingReturnStateException extends RuntimeException {
    BillingReturnStateException() {
        super("Invalid billing return state");
    }
}
