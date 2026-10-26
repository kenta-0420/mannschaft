package com.mannschaft.app.common.token;

/**
 * 発行された秘密トークンの「平文」と「保存用ハッシュ」の対。
 *
 * <p>この record が存在する理由は、発行と同時にハッシュを必ず手に入る形にして、
 * 「ハッシュ化を忘れて平文を DB に保存する」という事故を型の上で起こしにくくするためである。
 * 呼び出し元は {@link #plaintext()} を利用者（メール本文・URL 等）へ一度だけ渡し、
 * 永続化するのは {@link #hash()} だけにすること。</p>
 *
 * @param plaintext 利用者へ一度だけ渡す平文トークン（hex64）。永続化してはならない
 * @param hash      DB へ保存するハッシュ値（hex64）
 */
public record IssuedToken(String plaintext, String hash) {
}
