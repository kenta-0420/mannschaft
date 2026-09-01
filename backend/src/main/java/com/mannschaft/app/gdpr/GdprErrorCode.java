package com.mannschaft.app.gdpr;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F12.3 GDPR/個人情報管理のエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum GdprErrorCode implements ErrorCode {

    /** データエクスポートは1日1回まで */
    GDPR_001("GDPR_001", "データエクスポートは1日1回まで", Severity.WARN),

    /** エクスポート処理中（多重実行の状態競合のため409） */
    GDPR_002("GDPR_002", "エクスポート処理中です", Severity.WARN),

    /**
     * エクスポートレコードが存在しない（404）。
     *
     * <p>かつては「不在」「未完了」「期限切れ」の3意味で共用されていたが、どう写像しても
     * いずれかの経路が誤ったステータスを返すため分割した。本コードは<b>レコードが1件も無い</b>
     * 場合<b>のみ</b>に限定する。未完了は {@link #GDPR_009}（409）、期限切れは
     * {@link #GDPR_010}（410）を使うこと。</p>
     */
    GDPR_003("GDPR_003", "エクスポートデータが見つかりません", Severity.WARN),

    /** データエクスポートに失敗 */
    GDPR_004("GDPR_004", "データエクスポートに失敗しました", Severity.ERROR),

    /** パスワード認証が必要 */
    GDPR_005("GDPR_005", "パスワード認証が必要です", Severity.WARN),

    /** 管理者権限の移譲が必要（唯一のSYSTEM_ADMIN退会拒否・状態競合のため409） */
    GDPR_006("GDPR_006", "管理者権限の移譲が必要です", Severity.WARN),

    /** OTP認証がロック */
    GDPR_007("GDPR_007", "OTP認証がロックされました", Severity.WARN),

    // GDPR_008 は設計書 F12.3_gdpr_personal_data.md §7 が F01.9（後継保護者の指定が必要）用に
    // 予約済みの番号のため、ここでは使わずに 009 から採る。

    /**
     * エクスポートがまだダウンロードできる状態にない（409）。
     *
     * <p>レコードは存在するが {@code status != COMPLETED}、または S3Key が未設定の場合。
     * 「無い」のではなく「まだ出来ていない」という状態競合なので 409。GDPR_002
     * （処理中の多重実行拒否）がリクエスト側の競合であるのに対し、本コードは取得側の競合。</p>
     */
    GDPR_009("GDPR_009", "エクスポートデータはまだダウンロードできません", Severity.WARN),

    /**
     * エクスポートの保持期限が切れている（410）。
     *
     * <p>{@code expiresAt} を過ぎたエクスポート。かつては確かに存在したが期限で失効した、
     * という意味を正確に表すため 404 ではなく 410 GONE を返す（再取得しても復活しないことを
     * クライアントに伝える）。</p>
     */
    GDPR_010("GDPR_010", "エクスポートデータの保持期限が切れています", Severity.WARN),

    /**
     * 柱①「ADMINゼロ根治」AC1 — 他メンバー1人以上のスコープで唯一のADMINが退会要求した場合の409。
     *
     * <p>TODO 出陣で実装: {@code UserService#requestWithdrawal} 冒頭で
     * {@code RoleSuccessionService#checkNoLastAdminScopes} 相当のガードから投げる。
     * 正本: docs/architecture/account_purge_last_admin_succession.md §14。</p>
     */
    GDPR_011("GDPR_011", "退会前に処理が必要な組織があります", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
