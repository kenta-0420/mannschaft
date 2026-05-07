package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * WebAuthn資格情報削除イベント。
 * deleteCredential() 実行時に発行される。
 */
@Getter
public class WebAuthnCredentialRemovedEvent extends BaseEvent {

    private final Long userId;
    /** 削除された WebAuthn Credential の DB ID。 */
    private final Long credentialId;

    public WebAuthnCredentialRemovedEvent(Long userId, Long credentialId) {
        super();
        this.userId = userId;
        this.credentialId = credentialId;
    }
}
