package com.mannschaft.app.pointcard.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F18 個人ポイントカードウォレットのエラーコード定義。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.3
 *
 * <p>第二陣 2A では {@code POINT_CARD_001} のみを確保する。
 * 残りの番号（002〜009）は 2B / 3 の責務で順次追加される。
 */
@Getter
@RequiredArgsConstructor
public enum PointCardErrorCode implements ErrorCode {

    /**
     * ウォレット機能未有効化または規約バージョン不一致。
     *
     * <p>{@code is_enabled=false} または {@code terms_accepted_at=null}、
     * もしくは同意済み {@code terms_version} が現行バージョンと不一致の場合に発生する。
     * HTTP 403 を返却し、フロントはオプトイン画面 / 規約再同意画面に誘導する。
     */
    WALLET_NOT_ENABLED("POINT_CARD_001", "ウォレット機能が有効化されていません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
