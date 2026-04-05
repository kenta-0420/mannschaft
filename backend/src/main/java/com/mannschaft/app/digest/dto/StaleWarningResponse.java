package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェスト公開時の stale 検知結果。
 */
@Getter
@RequiredArgsConstructor
public class StaleWarningResponse {

    private final int editedSinceGeneration;
    private final int deletedSinceGeneration;
}
