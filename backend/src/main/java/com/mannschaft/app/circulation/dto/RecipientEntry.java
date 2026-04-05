package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 受信者エントリDTO。受信者追加時のユーザーIDとソート順を保持する。
 */
@Getter
@RequiredArgsConstructor
public class RecipientEntry {

    @NotNull
    private final Long userId;

    private final Integer sortOrder;
}
