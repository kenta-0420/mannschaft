package com.mannschaft.app.reflection.dto;

import jakarta.validation.constraints.Size;

/**
 * ブログ輸出リクエスト（F06.5・§7 #13 / §6.3）。
 *
 * <p>輸出時は内部で {@code CreateBlogPostRequest.visibility="PRIVATE"} を必ず明示設定する（§6.3）。
 * 元エントリは残し、{@code exported_blog_post_id} を記録する。再輸出は 409（MVP）。</p>
 *
 * @param title 輸出先ブログ記事のタイトル（任意・省略時はテーマ名＋対象日をサービス層で組み立て）
 */
public record ExportToBlogRequest(

        @Size(max = 200, message = "タイトルは200文字以内で入力してください")
        String title
) {
}
