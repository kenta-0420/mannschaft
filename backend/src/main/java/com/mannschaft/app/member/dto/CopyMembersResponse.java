package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * メンバーコピーレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CopyMembersResponse {

    private final int copiedCount;
    private final int skippedCount;
    private final List<Long> skippedUserIds;
}
