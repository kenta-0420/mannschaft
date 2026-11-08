package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログイン時にバックアップコードを 1 件消費したイベント（CMP-261012-0910 AC-12）。
 *
 * <p>監査 {@code MFA_BACKUP_CODE_USED} の記録と、本人宛セキュリティ通知メール
 * （{@code MFA_SECURITY_NOTICE}）の enqueue をトリガーする。平文コードは載せない。</p>
 */
@Getter
public class MfaBackupCodeUsedEvent extends BaseEvent {

    private final Long userId;
    /** 消費後の残り件数。 */
    private final int backupCodesRemaining;
    private final String ipAddress;
    private final String userAgent;

    public MfaBackupCodeUsedEvent(Long userId, int backupCodesRemaining, String ipAddress, String userAgent) {
        super();
        this.userId = userId;
        this.backupCodesRemaining = backupCodesRemaining;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
    }
}
