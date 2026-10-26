package com.mannschaft.app.role.dto;

/**
 * スコープ内で特定ロールを持つユーザーの連絡先（通知配送用の最小情報）。
 *
 * <p>Issue #2834 / CMP-056 第2群ロット1 で追加。他ドメインが {@code role} ドメインの
 * {@code UserRoleRepository} を直接注入せずに受信者を解決できるようにするための DTO
 * （越境は Service 経由・D-3 / D-5 準拠）。Entity や {@code Object[]} を漏らさない。</p>
 *
 * @param userId ユーザーID
 * @param email  メールアドレス（{@code null} / 空ならメール送信をスキップする想定）
 */
public record ScopeRoleUserContact(Long userId, String email) {
}
