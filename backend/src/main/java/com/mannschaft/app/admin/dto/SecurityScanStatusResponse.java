package com.mannschaft.app.admin.dto;

import java.time.Instant;

/**
 * セキュリティスキャン状態レスポンス。
 *
 * <p>GitHub Actions の OWASP Dependency-Check 週次スキャン（security-scan.yml）の
 * 最新実行状態をシステム管理画面に返すための DTO。</p>
 *
 * @param conclusion スキャン結論（"SUCCESS" | "FAILURE" | "IN_PROGRESS" | "UNKNOWN"）
 * @param runUrl     GitHub Actions の実行 URL（例: https://github.com/.../actions/runs/xxx）
 * @param runAt      最終実行日時（実行履歴がない場合は null）
 */
public record SecurityScanStatusResponse(
        String conclusion,
        String runUrl,
        Instant runAt
) {}
