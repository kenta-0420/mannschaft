package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * メンバープロフィール一括登録レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateMemberResponse {

    private final int createdCount;
    private final int skippedCount;
    private final List<Long> skippedUserIds;
}
