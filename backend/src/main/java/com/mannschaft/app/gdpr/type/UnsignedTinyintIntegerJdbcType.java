package com.mannschaft.app.gdpr.type;

import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.IntegerJdbcType;

/**
 * GDPR証跡のTINYINT UNSIGNED列を、0..255を保持できるIntegerとして読み書きする。
 * DDL照合型だけをTINYINTにし、INTEGERのsetInt/getIntによるbind/extractを継承する。
 */
public class UnsignedTinyintIntegerJdbcType extends IntegerJdbcType {

    @Override
    public int getDdlTypeCode() {
        return SqlTypes.TINYINT;
    }
}
