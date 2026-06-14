package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チーム slug リネームリクエスト（F01.2 §5.9.5）。
 *
 * <p>{@code PUT /api/v1/teams/{slug}/slug} のボディ。新しい slug を指定する。
 * 形式・予約語・一意性・履歴予約の検証は Service 層
 * （{@link com.mannschaft.app.common.util.SlugValidator} + {@code TeamService#renameSlug}）で行う。
 * 空文字は明確な誤用のため {@code @NotBlank} で弾く（作成時の「未指定＝自動生成」とは挙動が異なる）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RenameSlugRequest {

    @NotBlank
    private String newSlug;
}
