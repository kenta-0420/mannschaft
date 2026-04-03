package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会話開始リクエストDTO。
 * 参加者数に応じてバックエンドが自動で振り分ける:
 * <ul>
 *   <li>1名 → Kabine（DM / 1対1）: 既存チャンネルがあれば返却、なければ新規作成</li>
 *   <li>2名以上 → Zimmer（GROUP_DM / グループDM）: 常に新規作成</li>
 * </ul>
 */
@Getter
@NoArgsConstructor
public class StartConversationRequest {

    /** 会話相手のユーザーIDリスト（自分自身は含めない。1〜9名）。 */
    @NotEmpty
    @Size(min = 1, max = 9, message = "会話相手は1〜9名を指定してください")
    private List<Long> userIds;
}
