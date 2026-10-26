package com.mannschaft.app.proxyvote.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 結果確定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FinalizeRequest {

    /** 定足数未達の場合に強制確定するか */
    private final Boolean force;
}
