package com.mannschaft.app.jobmatching.controller.dto;

import java.time.LocalDateTime;

/**
 * 求人公開リクエスト。
 *
 * <p>{@code publishAt} を指定すると予約公開として扱う（公開時点チェックは Service で行う）。
 * null のときは即時公開（DRAFT → OPEN）として振る舞う。</p>
 */
public record PublishJobPostingRequest(LocalDateTime publishAt) {
}
