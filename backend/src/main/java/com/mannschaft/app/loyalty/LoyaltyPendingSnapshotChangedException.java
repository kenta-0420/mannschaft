package com.mannschaft.app.loyalty;

/** ロック時にPENDINGが入れ替わったため、TX終了後にFacadeが外部snapshotを再取得する内部制御。 */
public final class LoyaltyPendingSnapshotChangedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public LoyaltyPendingSnapshotChangedException() { super("申請の照会状態が変更されました"); }
}
