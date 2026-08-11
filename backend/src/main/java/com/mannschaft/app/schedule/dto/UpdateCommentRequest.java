package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** F03.16 予定コメント編集リクエスト（設計書 §4.4）。 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateCommentRequest {
    private String body;
}
