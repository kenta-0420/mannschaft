package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.social.announcement.visibility.AnnouncementFeedVisibility;

/**
 * {@link AnnouncementFeedVisibility} を {@link StandardVisibility} に正規化する（F02.6 / F08.9 P4b）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 対応表。</p>
 *
 * <p>{@code AnnouncementFeedVisibility} の各値は
 * {@link com.mannschaft.app.social.announcement.AnnouncementVisibility} 文字列定数と
 * 同一のセマンティクスを持ち、F00 正準ラダーへの写像は以下のとおり:</p>
 * <ul>
 *   <li>{@link AnnouncementFeedVisibility#PUBLIC} → {@link StandardVisibility#PUBLIC}</li>
 *   <li>{@link AnnouncementFeedVisibility#SUPPORTERS_AND_ABOVE} → {@link StandardVisibility#SUPPORTERS_AND_ABOVE}</li>
 *   <li>{@link AnnouncementFeedVisibility#MEMBERS_AND_ABOVE} → {@link StandardVisibility#MEMBERS_AND_ABOVE}</li>
 *   <li>{@link AnnouncementFeedVisibility#CUSTOM} → {@link StandardVisibility#CUSTOM}
 *       （F08.9 P4b ペイウォール連結: {@code evaluateCustom} で
 *       {@link com.mannschaft.app.payment.service.PaymentGateService#checkAccess} を呼ぶ）</li>
 * </ul>
 */
public final class AnnouncementFeedVisibilityMapper {

    private AnnouncementFeedVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * {@link AnnouncementFeedVisibility} を {@link StandardVisibility} に写像する。
     *
     * @param v 機能側 enum 値（non-null）
     * @return 対応する {@link StandardVisibility} 値
     */
    public static StandardVisibility toStandard(AnnouncementFeedVisibility v) {
        return switch (v) {
            case PUBLIC -> StandardVisibility.PUBLIC;
            case SUPPORTERS_AND_ABOVE -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            // MEMBERS_AND_ABOVE は「MEMBER 以上」＝応援者除外の内輪（F00 正準ラダーと同一閾値）。
            case MEMBERS_AND_ABOVE -> StandardVisibility.MEMBERS_AND_ABOVE;
            // F08.9 P4b ペイウォール連結。evaluateCustom で PaymentGateService を呼ぶ。
            case CUSTOM -> StandardVisibility.CUSTOM;
        };
    }
}
