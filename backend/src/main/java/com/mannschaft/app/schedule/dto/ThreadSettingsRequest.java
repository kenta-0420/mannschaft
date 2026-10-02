package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** F03.16 スレッド開閉リクエスト（設計書 §4.4）。 */
@Getter
@Setter
@NoArgsConstructor
public class ThreadSettingsRequest {
    private Boolean commentsEnabled;
}
