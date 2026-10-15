package com.mannschaft.app.tournament.scorekeeper.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 大会スコアキーパー指名の追加リクエスト DTO（F08.7 項目③）。
 *
 * <p>指名されたユーザーは当該大会のスコア入力（updateScore / player-stats / status /
 * batch / import）が可能になる。指名できるのは主催組織 ADMIN のみ。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateScorekeeperRequest {

    /** スコアキーパーに指名するユーザー ID。 */
    @NotNull
    private final Long userId;
}
