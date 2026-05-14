package com.mannschaft.app.pointcard.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F18 個人ポイントカードウォレットのエラーコード定義。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.3
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} の個別マッピングと
 * Severity ベースの既定（WARN=400 / ERROR=500）の組み合わせで決定する。
 * 個別 HTTP が必要なもの（403 / 404 / 409 など）は GlobalExceptionHandler に登録する。
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
    WALLET_NOT_ENABLED("POINT_CARD_001", "ウォレット機能が有効化されていません", Severity.WARN),

    /**
     * カード保有上限（200 枚）超過。HTTP 400。
     *
     * <p>1 ユーザーあたりの上限は設計書 §7.4 / DB 制約と整合して 200 枚。
     * 上限超過時は古いカードを削除するよう案内する。
     */
    CARD_LIMIT_EXCEEDED("POINT_CARD_002", "カード保有上限（200 枚）に達しています", Severity.WARN),

    /**
     * カードが見つからない（または他人のカード — IDOR 防止のため同じコードを返す）。HTTP 404。
     */
    CARD_NOT_FOUND("POINT_CARD_003", "指定されたカードは存在しません", Severity.WARN),

    /**
     * バーコード形式の値が不正。HTTP 400。
     * Bean Validation で大半は防げるが、enum 範囲外を経由した時の保険。
     */
    INVALID_BARCODE_FORMAT("POINT_CARD_004", "barcodeFormat の値が不正です", Severity.WARN),

    /**
     * カード番号がプロバイダー指定の正規表現に一致しない。HTTP 400。
     * fuzzy match で偶発的にマッチした場合は警告に留め保存を継続する設計のため、
     * 本コードはクライアントが明示的に provider を指定したケースのみ使用する。
     */
    INVALID_BARCODE_VALUE("POINT_CARD_005",
            "カード番号がプロバイダーの形式と一致しません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
