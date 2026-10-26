package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 壁紙設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class WallpaperSettingRequest {

    /** 壁紙ID（NULLでデフォルトに戻す） */
    private final Long wallpaperId;
}
