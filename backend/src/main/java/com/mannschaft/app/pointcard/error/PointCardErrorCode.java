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
 * 個別 HTTP が必要なもの（401 / 403 / 404 / 409 / 429 など）は GlobalExceptionHandler に登録する。
 *
 * <p>番号と HTTP は設計書 §6.3 に厳密に整合させている（第三陣 S3 で 2B 設計と整合化）:
 * <pre>
 *   001 WALLET_NOT_ENABLED        403
 *   002 INVALID_BARCODE_VALUE     400
 *   003 CARD_LIMIT_EXCEEDED       409
 *   004 GROUP_LIMIT_EXCEEDED      409
 *   005 GROUP_ITEM_LIMIT_EXCEEDED 409
 *   006 CARD_NOT_FOUND            404
 *   007 PROVIDER_NOT_FOUND        404
 *   008 RATE_LIMIT_EXCEEDED       429
 *   009 BIOMETRIC_REQUIRED        401
 * </pre>
 */
@Getter
@RequiredArgsConstructor
public enum PointCardErrorCode implements ErrorCode {

    /**
     * ウォレット機能未有効化または規約バージョン不一致。HTTP 403。
     *
     * <p>{@code is_enabled=false} または {@code terms_accepted_at=null}、
     * もしくは同意済み {@code terms_version} が現行バージョンと不一致の場合に発生する。
     * フロントはオプトイン画面 / 規約再同意画面に誘導する。
     */
    WALLET_NOT_ENABLED("POINT_CARD_001", "ウォレット機能が有効化されていません", Severity.WARN),

    /**
     * カード番号がプロバイダー指定の正規表現に一致しない。HTTP 400。
     *
     * <p>fuzzy match で偶発的にマッチした場合は警告に留め保存を継続する設計のため、
     * 本コードはクライアントが明示的に provider を指定したケースのみ使用する。
     * 設計書では従来別エラーとしていた {@code INVALID_BARCODE_FORMAT} は本コードに統合した。
     */
    INVALID_BARCODE_VALUE("POINT_CARD_002",
            "カード番号がプロバイダーの形式と一致しません", Severity.WARN),

    /**
     * カード保有上限（200 枚）超過。HTTP 409。
     *
     * <p>1 ユーザーあたりの上限は設計書 §6.2 / §7.4 と整合し 200 枚。
     * 上限超過時は古いカードを削除するよう案内する。
     */
    CARD_LIMIT_EXCEEDED("POINT_CARD_003", "カード保有上限（200 枚）に達しています", Severity.WARN),

    /**
     * グループ作成上限（50 個）超過。HTTP 409。
     */
    GROUP_LIMIT_EXCEEDED("POINT_CARD_004", "グループ作成上限（50 個）に達しています", Severity.WARN),

    /**
     * グループ内カード数上限（20 枚）超過。HTTP 409。
     */
    GROUP_ITEM_LIMIT_EXCEEDED("POINT_CARD_005",
            "グループ内カード数上限（20 枚）に達しています", Severity.WARN),

    /**
     * カードが見つからない（または他人のカード — IDOR 防止のため同じコードを返す）。HTTP 404。
     */
    CARD_NOT_FOUND("POINT_CARD_006", "指定されたカードは存在しません", Severity.WARN),

    /**
     * プロバイダーが見つからない／無効。HTTP 404。
     * {@code provider_id} 明示指定で対象が {@code is_active=false} か未登録の場合。
     */
    PROVIDER_NOT_FOUND("POINT_CARD_007", "指定されたプロバイダーは存在しません", Severity.WARN),

    /**
     * レートリミット超過。HTTP 429。
     *
     * <p>{@code PointCardRateLimitFilter} は現状直接 429 を返すため、サービス層からの投擲は任意。
     * 将来サービス層から制御したい場合に備え enum を確保する。
     */
    RATE_LIMIT_EXCEEDED("POINT_CARD_008", "アクセス頻度が高すぎます。しばらく待ってから再試行してください", Severity.WARN),

    /**
     * 生体認証が必要。HTTP 401。
     *
     * <p>{@code require_biometric_on_show=true} の状態で WebAuthn 通過なしに提示モードを
     * 起動しようとした場合に投げる。第五陣で提示モード実装時に Service から発火する。
     */
    BIOMETRIC_REQUIRED("POINT_CARD_009", "生体認証が必要です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
