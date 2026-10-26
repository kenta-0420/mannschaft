package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 週間テンプレート一覧レスポンスDTO（F03.4.2 §4 GET）。
 *
 * <p>{@code meta.totalTemplates}/{@code meta.limit}（500）は FE の上限表示用。</p>
 */
@Builder
@Getter
public class SlotTemplateListResponse {

    List<SlotTemplateResponse> templates;
    TemplateListMetaDto meta;

    /**
     * 一覧メタ情報。
     *
     * @param totalTemplates チームの現在のテンプレ行数
     * @param limit          1 チームあたりの上限行数（500）
     */
    public record TemplateListMetaDto(long totalTemplates, int limit) {}
}
