package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 共有リンクアクセスリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AccessLinkRequest {

    private final String password;
}
