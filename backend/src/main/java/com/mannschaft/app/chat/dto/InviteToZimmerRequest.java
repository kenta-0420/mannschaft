package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * KabineからZimmerへの招待リクエストDTO。
 * 既存のKabine（DM）を維持したまま、新しいZimmer（GROUP_DM）を作成する。
 */
@Getter
@NoArgsConstructor
public class InviteToZimmerRequest {

    /** 新たに招待するユーザーIDリスト（1〜8名）。 */
    @NotEmpty
    @Size(min = 1, max = 8, message = "招待するユーザーは1〜8名を指定してください")
    private List<Long> userIds;

    /**
     * 既存のKabineの会話履歴をZimmerに共有するか。
     * true の場合、KabineのメッセージがZimmerに転送コピーされる。
     */
    private boolean shareHistory;
}
