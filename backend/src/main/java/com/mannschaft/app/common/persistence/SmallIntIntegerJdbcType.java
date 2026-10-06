package com.mannschaft.app.common.persistence;

import org.hibernate.type.descriptor.jdbc.IntegerJdbcType;

import java.sql.Types;

/**
 * 既存SMALLINT列のJDBC型とJavaの整数幅を両立する。
 * setInt/getIntを継承し、符号なし16bit値を含む整数幅を保持する。
 * 型検証はSMALLINT、生成DDLは@Columnのportable型定義を使う。
 */
public class SmallIntIntegerJdbcType extends IntegerJdbcType {

    @Override
    public int getJdbcTypeCode() {
        return Types.SMALLINT;
    }
}
