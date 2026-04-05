package com.mannschaft.app.membership.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 会員証一覧のメタ情報。
 */
@Getter
@RequiredArgsConstructor
public class MemberCardListMeta {

    private final long totalActive;
    private final long totalSuspended;
    private final long totalRevoked;
}
