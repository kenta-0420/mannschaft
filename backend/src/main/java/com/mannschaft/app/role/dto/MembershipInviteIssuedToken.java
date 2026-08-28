package com.mannschaft.app.role.dto;

/**
 * 承諾型招待トークン発行結果。
 *
 * <p>role ドメインの Service API から JPA Entity を漏らさず、後続のチャットカード投稿と
 * クライアント応答に必要な最小情報だけを受け渡す。</p>
 *
 * @param id    招待トークン ID
 * @param token 招待トークン文字列
 */
public record MembershipInviteIssuedToken(Long id, String token) {
}
