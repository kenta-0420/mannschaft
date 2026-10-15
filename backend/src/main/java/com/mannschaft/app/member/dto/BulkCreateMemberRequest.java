package com.mannschaft.app.member.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * メンバープロフィール一括登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateMemberRequest {

    @NotNull
    private final Long teamPageId;

    @NotNull
    @Valid
    private final List<BulkMemberItem> members;

    @Getter
    @RequiredArgsConstructor
    public static class BulkMemberItem {

        private final Long userId;

        private final String displayName;

        private final String memberNumber;

        private final String photoS3Key;

        private final String bio;

        private final String position;

        private final String customFields;
    }
}
