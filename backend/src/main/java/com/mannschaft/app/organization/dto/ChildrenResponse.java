package com.mannschaft.app.organization.dto;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import lombok.Getter;

import java.util.List;

/**
 * 子組織一覧レスポンス（GET /api/v1/organizations/{id}/children 用）。
 *
 * <p>カーソルベースのページネーション。既存の {@link CursorPagedResponse.CursorMeta} を再利用する。</p>
 */
@Getter
public class ChildrenResponse extends ApiResponse<List<ChildOrganizationResponse>> {

    private final CursorPagedResponse.CursorMeta meta;

    public ChildrenResponse(List<ChildOrganizationResponse> data, CursorPagedResponse.CursorMeta meta) {
        super(data);
        this.meta = meta;
    }
}
