package com.mannschaft.app.knowledgebase.dto;

import com.mannschaft.app.knowledgebase.PageAccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * ナレッジベースページ更新リクエスト。
 */
public record UpdateKbPageRequest(
        @NotBlank @Size(max = 200) String title,
        String body,
        String icon,
        PageAccessLevel accessLevel,
        @NotNull Long version
) {}
