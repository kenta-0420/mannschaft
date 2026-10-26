package com.mannschaft.app.survey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * アンケート締切延長リクエスト DTO（F05.4 §4.7 extend）。
 *
 * <p>{@code new_deadline}（=新しい {@code expires_at}）と {@code version}（楽観的ロック用）を受け取る。
 * 短縮は不可で、現在の {@code expires_at} より後の日時のみ受け付ける。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ExtendDeadlineRequest {

    /**
     * 新しい締切（{@code expires_at}）。現在より後の日時のみ受付。
     */
    @JsonProperty("new_deadline")
    @NotNull
    private LocalDateTime newDeadline;

    /**
     * 楽観的ロック用バージョン。
     */
    private Long version;
}
