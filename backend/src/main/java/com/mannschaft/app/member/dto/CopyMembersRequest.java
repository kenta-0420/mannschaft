package com.mannschaft.app.member.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メンバーコピーリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CopyMembersRequest {

    @NotNull
    private final Long sourcePageId;
}
