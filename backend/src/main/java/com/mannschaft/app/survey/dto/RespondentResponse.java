package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * アンケート回答者一覧の1エントリ。
 * F05.4 §7.2 「未回答者一覧の可視化」で使用。
 *
 * <p>MEMBER 経路（{@code unresponded_visibility = ALL_MEMBERS}）では、
 * 未回答者のみが {@code hasResponded = false} で返却され、{@code respondedAt} は null。
 * ADMIN+ / 作成者経路では、全配信対象者を回答有無付きで返却する。</p>
 */
@Getter
@RequiredArgsConstructor
public class RespondentResponse {

    /** 対象ユーザーID。 */
    private final Long userId;

    /** 表示名。 */
    private final String displayName;

    /** アバターURL（設定なしの場合 null）。 */
    private final String avatarUrl;

    /** 回答済みかどうか。 */
    private final Boolean hasResponded;

    /**
     * 回答日時。
     * 未回答 / MEMBER 経路の場合は null。
     */
    private final LocalDateTime respondedAt;
}
