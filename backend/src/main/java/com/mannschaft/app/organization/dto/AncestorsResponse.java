package com.mannschaft.app.organization.dto;

import com.mannschaft.app.common.ApiResponse;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 祖先組織一覧レスポンス（GET /api/v1/organizations/{id}/ancestors 用）。
 *
 * <p>{@code data} は root（最上位）→ 直近の親 の順で並ぶ。
 * パンくず UI でそのまま左から右に表示できる順序。</p>
 */
@Getter
public class AncestorsResponse extends ApiResponse<List<AncestorOrganizationResponse>> {

    private final AncestorsMeta meta;

    public AncestorsResponse(List<AncestorOrganizationResponse> data, AncestorsMeta meta) {
        super(data);
        this.meta = meta;
    }

    /**
     * 祖先一覧メタ情報。
     */
    @Getter
    @RequiredArgsConstructor
    public static class AncestorsMeta {

        /** 祖先チェーンの実際の深さ（{@code data} の要素数）。トップレベル組織は 0。 */
        private final int depth;

        /** {@code true} の場合、{@code app.org.max-depth} 到達による打ち切りが発生したことを示す。 */
        private final boolean truncated;
    }
}
