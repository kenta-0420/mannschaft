package com.mannschaft.app.common.persistence;

import org.hibernate.type.descriptor.jdbc.BigIntJdbcType;

import java.sql.Types;

/**
 * 既存INT UNSIGNED列のJDBC型とJavaのLong幅を両立する。
 * setLong/getLongを継承し、符号なし32bit値を保持する。
 * 型検証はINTEGER、生成DDLは@Columnのportable型定義を使う。
 */
public class IntegerLongJdbcType extends BigIntJdbcType {

    @Override
    public int getJdbcTypeCode() {
        return Types.INTEGER;
    }
}
