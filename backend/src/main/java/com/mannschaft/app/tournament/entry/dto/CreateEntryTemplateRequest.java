package com.mannschaft.app.tournament.entry.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * エントリーテンプレート作成リクエストDTO。
 *
 * <p>F08.7 Phase 9-B: チームごとに最大5件のエントリーテンプレートを管理する。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEntryTemplateRequest {

    /** テンプレート名（必須、最大50文字） */
    @NotBlank
    @Size(max = 50)
    String name;

    /** テンプレート説明（最大200文字、nullable） */
    @Size(max = 200)
    String description;

    /** 並び順（デフォルト: 0） */
    @Builder.Default
    Short sortOrder = 0;

    /** テンプレートメンバー一覧（必須） */
    @NotNull
    @Valid
    List<TemplateMemberItem> members;

    /**
     * テンプレートメンバー1件の明細DTO。
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplateMemberItem {

        /** ユーザーID（必須） */
        @NotNull
        Long userId;

        /** 背番号（nullable） */
        Integer jerseyNumber;

        /** ポジション（nullable） */
        String position;

        /** 並び順（デフォルト: 0） */
        @Builder.Default
        Short sortOrder = 0;
    }
}
