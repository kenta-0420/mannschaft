package com.mannschaft.app.visibility.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 公開範囲テンプレート一覧レスポンス DTO。
 * ユーザーのカスタムテンプレートとシステムプリセットを分けて返す。
 */
@Getter
@Builder
public class VisibilityTemplateListResponse {

    /** ユーザーのカスタムテンプレート一覧（新しい順） */
    private final List<VisibilityTemplateSummaryResponse> userTemplates;

    /** システムプリセット一覧（ID昇順） */
    private final List<VisibilityTemplateSummaryResponse> systemPresets;
}
