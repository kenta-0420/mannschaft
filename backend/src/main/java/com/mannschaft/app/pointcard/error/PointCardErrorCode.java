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
 *   012 STAMP_INVALID_PROVIDER    400
 *   013 STAMP_INVALID_PROVIDER_TYPE 400
 *   014 STAMP_DELTA_ZERO          400
 *   015 BALANCE_INVALID_PROVIDER_TYPE 400
 *   016 BALANCE_DELTA_ZERO       400
 *   017 INSUFFICIENT_BALANCE     400
 *   018 BALANCE_LIMIT_EXCEEDED   409
 *   019 TOKEN_NOT_FOUND          404
 *   020 REFUND_EXCEEDS_ORIGINAL 409
 * </pre>
 *
 * <p>番号 010 / 011 は 2B（プロバイダー CRUD）用に予約。
 * <p>番号 015〜018 は Phase 3（残高型）用。
 * <p>番号 019 は Phase 3 第二陣 2A（QR 自動特定 = 顧客側一時トークン）用。
 * <p>番号 019 は Phase 3 第二陣 2A（一時トークン API）用に予約。
 * <p>番号 020 は Phase 3 第二陣 2B（残高型 REFUND 上限超過）用。
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
    BIOMETRIC_REQUIRED("POINT_CARD_009", "生体認証が必要です", Severity.WARN),

    /**
     * 1 組織あたりの自店プロバイダー作成上限超過。HTTP 409。
     *
     * <p>1 組織あたりの上限は 20 個（Phase 2 S2B 設計）。
     * 既存の停止済（{@code is_active=false}）プロバイダーはカウントしない。
     * 上限到達時は古いプロバイダーの完全削除運用は無いため、停止していないものを整理して再試行する。
     */
    PROVIDER_LIMIT_EXCEEDED("POINT_CARD_010",
            "1 組織あたりのプロバイダー作成上限（20 個）に達しています", Severity.WARN),

    /**
     * 指定された組織にプロバイダーが所属していない。HTTP 404（IDOR 防止）。
     *
     * <p>パスの {@code orgId} と当該プロバイダーの {@code organization_id} が一致しない場合に投げる。
     * 「他組織のプロバイダーを覗こうとした」状態は 403 ではなく 404 を返す慣習（IDOR 抑止）。
     */
    PROVIDER_NOT_OWNED("POINT_CARD_011",
            "このプロバイダーは指定された組織のものではありません", Severity.WARN),

    /**
     * スタンプ押印対象のカードに自店プロバイダーが紐付いていない。HTTP 400。
     *
     * <p>{@code user_point_cards.provider_id IS NULL} の自由入力カードは
     * 押印不可。Phase 2 第二陣 2C で導入。
     */
    STAMP_INVALID_PROVIDER("POINT_CARD_012",
            "このカードには自店プロバイダーが紐付いていません", Severity.WARN),

    /**
     * スタンプ押印対象のプロバイダー種別が {@code SELF_ISSUED_STAMP} でない。HTTP 400。
     *
     * <p>外部プロバイダー（EXTERNAL）や残高型（SELF_ISSUED_BALANCE）への押印は不可。
     */
    STAMP_INVALID_PROVIDER_TYPE("POINT_CARD_013",
            "スタンプ押印は SELF_ISSUED_STAMP プロバイダーでのみ可能です", Severity.WARN),

    /**
     * スタンプ delta が 0。HTTP 400。
     *
     * <p>DB CHECK 制約でも防げるがアプリ層で事前に弾く（無意味なトランザクション抑止）。
     */
    STAMP_DELTA_ZERO("POINT_CARD_014", "delta は 0 にできません", Severity.WARN),

    /**
     * 残高型操作対象のプロバイダー種別が {@code SELF_ISSUED_BALANCE} でない。HTTP 400。
     *
     * <p>スタンプ型（SELF_ISSUED_STAMP）や外部プロバイダー（EXTERNAL）への残高操作は不可。
     * Phase 3 で導入。
     */
    BALANCE_INVALID_PROVIDER_TYPE("POINT_CARD_015",
            "残高操作は SELF_ISSUED_BALANCE プロバイダーでのみ可能です", Severity.WARN),

    /**
     * 残高操作の delta が 0。HTTP 400。
     *
     * <p>DB CHECK 制約でも防げるがアプリ層で事前に弾く（無意味なトランザクション抑止）。
     */
    BALANCE_DELTA_ZERO("POINT_CARD_016", "delta は 0 にできません", Severity.WARN),

    /**
     * 残高不足（balance_after が負数になる SPENT 操作）。HTTP 400。
     */
    INSUFFICIENT_BALANCE("POINT_CARD_017",
            "残高が不足しています", Severity.WARN),

    /**
     * 残高上限超過（累計 10,000,000 円）。HTTP 409。
     */
    BALANCE_LIMIT_EXCEEDED("POINT_CARD_018",
            "残高上限（10,000,000 円）に達しています", Severity.WARN),

    /**
     * 一時トークンが見つからない / 期限切れ / 使用済み。HTTP 404。
     *
     * <p>Phase 3 第二陣 2A の QR 自動特定で利用。
     * 顧客側で発行した 5 分 TTL の UUID トークンを店主側が resolve した際、
     * Valkey に存在しないか TTL 切れ、または既に消費済（GETDEL で削除済）の場合に投げる。
     * 不存在 / 期限切れ / 使用済の区別はクライアントに開示しない（情報漏洩防止）。
     */
    TOKEN_NOT_FOUND("POINT_CARD_019",
            "一時トークンが見つからないか、期限切れまたは使用済みです", Severity.WARN),

    /**
     * REFUND（返金）金額が元 SPENT イベントの利用額を超えている。HTTP 409。
     *
     * <p>多重返金や水増し返金を防ぐため、{@code refund_of_event_id} で示される元 event の
     * {@code |delta|} を上限として、既存返金累計 + 今回返金額がそれを超える場合に投擲する。
     * Phase 3 第二陣 2B で導入。
     */
    REFUND_EXCEEDS_ORIGINAL("POINT_CARD_020",
            "返金額が元の利用額を超えています", Severity.WARN),

    /**
     * 同義語の正規化済キーが既に登録されている。HTTP 409。
     *
     * <p>{@code point_card_provider_synonyms.synonym_normalized} は UNIQUE 制約があり
     * DB 層でも検出可能だが、運営マスタ管理 UI からの操作では事前に明確な
     * エラーメッセージを返すためアプリ層でも重複チェックする。
     * Phase 4 第三陣 S3 で導入。
     */
    SYNONYM_DUPLICATE("POINT_CARD_021",
            "この同義語は既に登録されています", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
