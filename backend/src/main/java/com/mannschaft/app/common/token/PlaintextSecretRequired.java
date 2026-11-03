package com.mannschaft.app.common.token;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 平文の保持が機能の本質として必要な秘密に付ける<b>恒久除外</b>の宣言（CMP-260826-1457）。
 *
 * <p>HMAC 署名鍵・外部 API 呼び出し用の鍵・保有者本人に何度でも見せる QR など、
 * ハッシュ化すると機能が成り立たない列（陣立て書 1-C）にだけ付ける。番人
 * {@code PlaintextSecretTokenGuardArchTest} は、この注釈の {@link #reason()} が空のとき違反として扱う。</p>
 *
 * <p>W0 試練の段階では型の宣言だけを置く（番人の判定は出陣で実装する）。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface PlaintextSecretRequired {

    /**
     * 平文で保持しなければならない理由。空文字は不可（番人が違反として扱う）。
     *
     * @return 理由
     */
    String reason();
}
