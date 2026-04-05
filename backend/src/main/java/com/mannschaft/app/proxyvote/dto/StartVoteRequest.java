package com.mannschaft.app.proxyvote.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 議案投票開始リクエストDTO（MEETING モード）。
 */
@Getter
@RequiredArgsConstructor
public class StartVoteRequest {

    /** 投票制限時間（秒）。未指定の場合は ADMIN が手動で end-vote */
    private final Integer durationSeconds;
}
