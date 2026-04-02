package com.mannschaft.app.auth;

/**
 * ユーザー関連の定数クラス。
 */
public final class UserConstants {

    private UserConstants() {}

    /** センチネルユーザーID（退会済みユーザーの匿名化代替） */
    public static final Long SENTINEL_USER_ID = 0L;
}
