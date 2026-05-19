package com.mannschaft.app.gdpr.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザーアカウント物理削除（GDPR 30 日経過バッチ）完了イベント。
 *
 * <p>退会即時匿名化を表す {@link com.mannschaft.app.auth.event.UserAnonymizedEvent} とは別物。
 * 本イベントは、{@code AccountPurgeService#purgeUser} がユーザー本体を物理削除しきった
 * タイミングで発火する。各ドメインの {@code *PurgeEventListener} がこれを購読し、
 * 自ドメイン配下の越境データ（user_id を持つ行）を安全弁メソッド経由で削除する。</p>
 *
 * <p><b>payload 設計:</b>
 * <ul>
 *   <li>{@code userId} — 物理削除完了したユーザーID（必須、null 不可）</li>
 *   <li>{@code emailHash} — 監査ログ突合用 SHA-256 ハッシュ（PII 残存防止のため平文 email は載せない）</li>
 * </ul>
 * </p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §3.1 / §3.2 / §4 Phase B</p>
 */
@Getter
public class AccountPurgedEvent extends BaseEvent {

    private final Long userId;
    /** 監査ログ突合用の SHA-256 ハッシュ（平文 email は載せない）。 */
    private final String emailHash;

    public AccountPurgedEvent(Long userId, String emailHash) {
        super();
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        this.userId = userId;
        this.emailHash = emailHash;
    }
}
