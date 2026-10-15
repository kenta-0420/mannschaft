package com.mannschaft.app.tournament.entry.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * エントリーテンプレート適用リクエストDTO。
 *
 * <p>F08.7 Phase 9-B: 保存済みテンプレートをエントリー表に適用する。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyTemplateRequest {

    /** 適用するテンプレートID（必須） */
    @NotNull
    UUID templateId;

    /**
     * 既存エントリーを上書きするかどうか。
     * false（デフォルト）の場合は既存エントリー済みユーザーはスキップする。
     */
    @Builder.Default
    boolean overwriteExisting = false;
}
