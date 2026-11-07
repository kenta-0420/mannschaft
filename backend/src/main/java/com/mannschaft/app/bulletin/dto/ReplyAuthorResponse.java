package com.mannschaft.app.bulletin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 返信投稿者の表示情報。匿名投稿者のIDと未設定アバターはnullを許容する。 */
@Getter
@RequiredArgsConstructor
public class ReplyAuthorResponse {
    @Schema(types = {"integer", "null"}, format = "int64")
    private final Long id;
    private final String displayName;
    @Schema(types = {"string", "null"})
    private final String avatarUrl;
}
