package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 下書き自動保存リクエストDTO。エディタの30秒間隔自動保存で使用。
 */
@Getter
@RequiredArgsConstructor
public class AutoSaveRequest {

    @Size(max = 200)
    private final String title;

    @Size(max = 50000)
    private final String body;

    @Size(max = 500)
    private final String excerpt;

    /** 楽観的ロック用バージョン番号 */
    private final Integer version;
}
