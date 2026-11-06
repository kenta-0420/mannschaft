package com.mannschaft.app.role.dto;

/**
 * 承諾型招待トークン発行結果。
 *
 * <p>role ドメインの Service API から JPA Entity を漏らさず、後続のチャットカード投稿と
 * クライアント応答に必要な最小情報だけを受け渡す。</p>
 *
 * <p>宛先指定型は平文トークンを持ち回らない。宛先本人は招待 ID と認証済み本人の照合で承諾・辞退する。</p>
 *
 * @param id 招待トークン ID
 */
public record MembershipInviteIssuedToken(Long id) {
}
