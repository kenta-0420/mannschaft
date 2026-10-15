package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * F19.1 Phase 7: ブログ投稿の public_visible フラグ切替リクエスト DTO。
 *
 * <p>投稿者本人のみ操作可能（権限チェックは {@link com.mannschaft.app.cms.service.BlogPostService} 層で実施）。</p>
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePublicVisibleRequest {

    /**
     * 公開設定フラグ。
     * true=公開ページ・sitemap・OGP に表示する / false=非表示にする。
     */
    @NotNull(message = "publicVisible は必須です")
    private Boolean publicVisible;
}
