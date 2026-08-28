package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * F03.16 予定コメント投稿・返信リクエスト（設計書 §4.2）。
 *
 * <p>本文の空/長さ検証・{@code parentId} の UUID パース・{@code mentionedUserIds} の上限検証は
 * すべて {@code ScheduleCommentService} 側で行う（bean validation に頼ると、
 * 存在秘匿より前に 400 が確定してしまい、404 で秘匿すべき経路と衝突しうるため）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(name = "CreateScheduleCommentRequest")
public class CreateScheduleCommentRequest {
    private String body;
    private String parentId;
    private List<Long> mentionedUserIds;
}
