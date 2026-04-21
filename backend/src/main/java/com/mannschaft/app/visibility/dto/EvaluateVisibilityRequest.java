package com.mannschaft.app.visibility.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 公開範囲評価リクエスト DTO。
 *
 * <p>指定テンプレートに対して、特定ユーザーが閲覧可能かどうかを評価するためのリクエスト。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateVisibilityRequest {

    /** 閲覧可能かを確認したいユーザーID */
    @NotNull
    private Long targetUserId;

    /** テンプレートの所有者ユーザーID（投稿者ID） */
    @NotNull
    private Long ownerUserId;
}
