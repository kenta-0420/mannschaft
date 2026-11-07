package com.mannschaft.app.moduleevent.api.dto;

import lombok.Getter;

import java.util.Objects;

/**
 * 現在許可された通知対象の ID と、業務 resolver が返す配信 locale。
 *
 * <p>locale は ja / en / zh / ko / es / de の IETF tag とする。
 * 正規化は notification adapter 境界の既存機構へ委ね、M に言語分類や翻訳表を増やさない。
 * 氏名・メールアドレス等の PII や通知内部型は持たない。</p>
 */
@Getter
public final class ModuleEventNoticeRecipient {
    private final Long userId;
    private final String locale;

    public ModuleEventNoticeRecipient(Long userId, String locale) {
        this.userId = Objects.requireNonNull(userId, "受信者 ID は必須");
        this.locale = Objects.requireNonNull(locale, "配信 locale は必須");
    }
}
