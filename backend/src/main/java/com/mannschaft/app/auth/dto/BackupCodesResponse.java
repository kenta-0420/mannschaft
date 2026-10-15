package com.mannschaft.app.auth.dto;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * バックアップコード一覧レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class BackupCodesResponse {

    private final List<String> backupCodes;
}
