package com.mannschaft.app.common.persistence;

import org.hibernate.type.descriptor.jdbc.SmallIntJdbcType;

import java.sql.Types;

/**
 * 既存TINYINT UNSIGNED列のJDBC型とJavaの整数幅を両立する。
 * setShort/getShortを継承し、整数幅を保持する。
 * 型検証はTINYINT、生成DDLは@Columnのportable型定義を使う。
 */
public class TinyIntShortJdbcType extends SmallIntJdbcType {

    @Override
    public int getJdbcTypeCode() {
        return Types.TINYINT;
    }
}
