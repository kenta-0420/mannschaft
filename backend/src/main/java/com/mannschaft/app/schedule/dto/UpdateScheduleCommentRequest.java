package com.mannschaft.app.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** F03.16 予定コメント編集リクエスト（設計書 §4.4）。 */
@Getter
@Setter
@NoArgsConstructor
@Schema(name = "UpdateScheduleCommentRequest")
public class UpdateScheduleCommentRequest {
    private String body;
}
