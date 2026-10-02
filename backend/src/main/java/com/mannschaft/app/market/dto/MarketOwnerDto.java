package com.mannschaft.app.market.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 公開札の主催情報（PII 抑制・02_api_design §3.1 / §11.2）。
 *
 * <p>PERSONAL は内部 user ID を公開しないため {@code scopeId=null} とし、Jackson でもキー自体を
 * 出力しない。TEAM / ORGANIZATION は既存 API 互換として scope ID を維持する。</p>
 */
@Getter
@AllArgsConstructor
public class MarketOwnerDto {

    /** スコープ種別（PERSONAL / TEAM / ORGANIZATION）。 */
    private final String scopeType;

    /** スコープID（チームID / 組織ID）。PERSONAL は内部 ID 非開示のため null。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final Long scopeId;

    /** 公称名（チーム名 / 組織名）。 */
    private final String displayName;

    /** アイコンURL（任意）。 */
    private final String iconUrl;
}
