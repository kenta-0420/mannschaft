package com.mannschaft.app.digest.dto;

import com.mannschaft.app.common.CursorPagedResponse;
import lombok.Getter;

import java.util.List;

/**
 * ダイジェスト一覧レスポンス（AI クォータ情報付き）。
 */
@Getter
public class DigestListResponse {

    private final List<DigestSummaryResponse> data;
    private final CursorPagedResponse.CursorMeta meta;
    private final AiQuotaResponse aiQuota;

    public DigestListResponse(List<DigestSummaryResponse> data,
                              CursorPagedResponse.CursorMeta meta,
                              AiQuotaResponse aiQuota) {
        this.data = data;
        this.meta = meta;
        this.aiQuota = aiQuota;
    }
}
