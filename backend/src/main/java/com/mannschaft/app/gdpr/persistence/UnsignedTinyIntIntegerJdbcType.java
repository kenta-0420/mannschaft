package com.mannschaft.app.gdpr.persistence;

import org.hibernate.type.descriptor.jdbc.IntegerJdbcType;

import java.sql.Types;

/**
 * GDPR retry_countの既存TINYINT UNSIGNED列をIntegerで読み書きする。
 *
 * <p>型判定はTINYINTに合わせ、0〜255を符号付きbyteへ狭めないよう
 * IntegerJdbcTypeのsetInt/getIntを維持する。対象fieldだけに指定する。</p>
 */
public class UnsignedTinyIntIntegerJdbcType extends IntegerJdbcType {

    @Override
    public int getJdbcTypeCode() {
        return Types.TINYINT;
    }
}
