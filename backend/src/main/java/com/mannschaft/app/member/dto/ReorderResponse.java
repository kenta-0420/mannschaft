package com.mannschaft.app.member.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンバー並び替えレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReorderResponse {

    private final int updatedCount;
}
