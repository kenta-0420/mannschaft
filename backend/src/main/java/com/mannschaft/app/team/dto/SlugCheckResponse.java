package com.mannschaft.app.team.dto;

import java.util.List;

/**
 * スラッグ可用性チェック API のレスポンス DTO。
 *
 * <p>チーム・組織の両方で共用する。</p>
 *
 * @param available   true = 使用可能, false = 既に使用中
 * @param suggestions 使用不可の場合の代替候補スラッグ（最大3件）。使用可能な場合は空リスト。
 */
public record SlugCheckResponse(
        boolean available,
        List<String> suggestions
) {}
