package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: 公開札の主催（チーム/組織）情報（PII 抑制・02_api_design §3.1）。
 *
 * <p>チーム/組織の<strong>公称名＋アイコン</strong>のみ。作成者個人名・連絡先は一切含めない
 * （§04_security §1.3）。</p>
 */
@Getter
@AllArgsConstructor
public class MarketOwnerDto {

    /** スコープ種別（TEAM / ORGANIZATION）。 */
    private final String scopeType;

    /** スコープID（チームID / 組織ID）。 */
    private final Long scopeId;

    /** 公称名（チーム名 / 組織名）。 */
    private final String displayName;

    /** アイコンURL（任意）。 */
    private final String iconUrl;
}
