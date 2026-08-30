package com.mannschaft.app.market;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F22.1 市（Market）レイヤ固有のエラーコード（02_api_design §8）。
 *
 * <p>札立て・札下げ本体のバリデーションは {@code RECRUITMENT_*} を踏襲し、
 * 市レイヤ固有（地域整合・フレンド宛先・公開API）のみ {@code MARKET_*} 名前空間で新設する。</p>
 *
 * <p>HTTP ステータスは {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} で明示マッピングする
 * （MARKET_001/002/005=400, MARKET_003/004=403, MARKET_404=404）。{@link Severity#WARN} 既定の
 * 400 と一致するものも宣言的に登録して挙動を固定する。</p>
 */
@Getter
@RequiredArgsConstructor
public enum MarketErrorCode implements ErrorCode {

    /** city_code がマスタ不在 / prefecture_code と不整合（400）。 */
    REGION_INVALID("MARKET_001", "地域コードが不正です（市区町村マスタ不在、または都道府県と不整合）", Severity.WARN),

    /** visibility='FRIEND_TEAMS_ONLY' で friend_targets が 0 件（400）。 */
    FRIEND_TARGETS_REQUIRED("MARKET_002", "フレンド限定の非公開札には宛先を1件以上指定してください", Severity.WARN),

    /** フレンド未成立のチームを宛先指定（403）。 */
    FRIEND_NOT_ESTABLISHED("MARKET_003", "フレンド関係が成立していないチームを宛先に指定できません", Severity.WARN),

    /** 他チーム所有のフレンドフォルダを宛先指定（403）。 */
    FOLDER_NOT_OWNED("MARKET_004", "自チームが所有していないフレンドフォルダは宛先に指定できません", Severity.WARN),

    /** visibility='FRIEND_TEAMS_ONLY' なのに distribution_targets を併用指定（400）。 */
    FRIEND_DISTRIBUTION_TARGETS_CONFLICT(
            "MARKET_005", "フレンド限定の非公開札に配信対象（distribution_targets）は併用できません", Severity.WARN),

    PERSONAL_PAYMENT_DISABLED("MARKET_006", "個人の札では現在、謝礼決済と受領者を指定できません", Severity.WARN),

    SELF_APPLICATION_FORBIDDEN("MARKET_007", "自分が立てた個人の札には応募できません", Severity.WARN),

    PERSONAL_VISIBILITY_NOT_ALLOWED("MARKET_008", "個人札に指定できない公開範囲または公開先です", Severity.WARN),

    /** 公開市で対象の札が存在しない / 非公開のため存在秘匿（404）。 */
    LISTING_NOT_FOUND("MARKET_404", "対象の札が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
