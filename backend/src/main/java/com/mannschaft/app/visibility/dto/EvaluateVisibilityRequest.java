package com.mannschaft.app.visibility.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 公開範囲評価リクエスト DTO。
 *
 * <p>指定テンプレートに対して、特定ユーザーが閲覧可能かどうかを評価するためのリクエスト。</p>
 *
 * <p>認可根治 Wave4: 旧 {@code ownerUserId} フィールドは client 供給値を Service にそのまま
 * 渡していたため、他人の ID を詐称して当該ユーザーの関係グラフ（所属チーム/友達）を
 * プレビュー経由で列挙できる IDOR だった。owner は常にサーバー確定値
 * （{@code SecurityUtils.getCurrentUserId()}）に固定するため本 DTO からは除去した。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class EvaluateVisibilityRequest {

    /** 閲覧可能かを確認したいユーザーID */
    @NotNull
    private Long targetUserId;
}
