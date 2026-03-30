package com.mannschaft.app.knowledgebase.dto;

import com.mannschaft.app.knowledgebase.PageAccessLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * ナレッジベースページ作成リクエスト。
 */
public record CreateKbPageRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Pattern(regexp = "[a-z0-9-]+") @Size(max = 200) String slug,
        String body,
        String icon,
        PageAccessLevel accessLevel,
        Long parentId,
        Long templateId
) {}
