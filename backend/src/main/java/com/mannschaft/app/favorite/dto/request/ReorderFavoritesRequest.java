package com.mannschaft.app.favorite.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * お気に入り並び替えリクエスト。
 */
@Getter
@RequiredArgsConstructor
public class ReorderFavoritesRequest {

    /** 新しい表示順でのお気に入りIDリスト。 */
    @NotEmpty
    private final List<UUID> orderedIds;
}
