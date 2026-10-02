package com.mannschaft.app.analytics;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム/組織アクセス解析（F10.8）のエラーコード。
 *
 * <p>既存 {@link AnalyticsErrorCode}（経営分析 F10.4・{@code ANALYTICS_xxx}）とは対象・データソースが
 * 完全に分離しているため、プレフィクスを衝突しない {@code TEAMANALYTICS_xxx} とする
 * （設計書 §6）。HTTP マッピングは {@code GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} に追記する。</p>
 *
 * <p><b>担当分界（二の陣 / 参の陣）</b>: 本 enum は二の陣（Service/Config 中枢）が定義する
 * {@code TEAMANALYTICS_001}（認可 404 秘匿）のみを先行して置く。集計取得・計測ビーコンの
 * Controller/DTO を担当する参が、下記の {@code TEAMANALYTICS_002}（日付範囲不正 400）・
 * {@code TEAMANALYTICS_003}（ビーコン body 不正 400）を追記する（設計書 §6 の表に沿う）。
 * また {@code GlobalExceptionHandler#ERROR_CODE_STATUS_MAP} への
 * {@code TEAMANALYTICS_001 → NOT_FOUND(404)} 登録は参が Controller 結線時に併せて行うこと
 * （本 enum 単独では HTTP ステータス写像は効かない）。</p>
 *
 * @see AnalyticsErrorCode 経営分析（F10.4）側のエラーコード（別系統）
 */
@Getter
@RequiredArgsConstructor
public enum TeamOrgAnalyticsErrorCode implements ErrorCode {

    /**
     * スコープが存在しない / 非メンバーによる存在秘匿（IDOR 隠蔽・GET 集計取得）。
     *
     * <p>非メンバーが他スコープの analytics を叩いた場合、403（＝そのスコープは存在するが権限が無い、を露呈）
     * ではなく 404 を返し、集計データの存在（＝そのスコープが計測対象として稼働している事実）を秘匿する
     * （設計書 §3.3 の設計判断）。存在しない slug も同じ 404 に写像し、両者を攻撃者が区別できないようにする。</p>
     */
    TEAMANALYTICS_001("TEAMANALYTICS_001", "指定されたスコープのアクセス解析は取得できません", Severity.WARN),

    /**
     * 日付範囲不正（dateFrom > dateTo）。HTTP 400 で返す（設計書 §6・AC-11）。
     * GlobalExceptionHandler#ERROR_CODE_STATUS_MAP に BAD_REQUEST を登録済み。
     */
    TEAMANALYTICS_002("TEAMANALYTICS_002", "開始日は終了日より前に指定してください", Severity.WARN),

    /**
     * 計測ビーコンリクエスト不正（ENUM 外・url 絶対 URL 等）。HTTP 400 で返す（設計書 §6・AC-04・AC-22）。
     * GlobalExceptionHandler#ERROR_CODE_STATUS_MAP に BAD_REQUEST を登録済み。
     */
    TEAMANALYTICS_003("TEAMANALYTICS_003", "計測ビーコンのリクエストが不正です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
