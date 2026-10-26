package com.mannschaft.app.visibility.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 公開範囲評価レスポンス DTO。
 *
 * <p>指定テンプレートに対して、対象ユーザーが閲覧可能かどうかの評価結果を返す。</p>
 */
@Getter
@Builder
@AllArgsConstructor
public class EvaluateVisibilityResponse {

    /** 評価対象のテンプレートID */
    private Long templateId;

    /** 閲覧可能かを確認したユーザーID */
    private Long targetUserId;

    /** 閲覧可能かどうかの評価結果 */
    private boolean canView;
}
