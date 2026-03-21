package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンバー検索結果レスポンスDTO（コンボボックス用）。
 */
@Getter
@RequiredArgsConstructor
public class MemberLookupResponse {

    private final Long memberProfileId;
    private final Long userId;
    private final String displayName;
    private final String memberNumber;
    private final String position;
    private final String photoS3Key;
}
