package com.mannschaft.app.moduleevent.api.dto;

/** 業務側の永続 workflow による復旧根拠。UNKNOWN を復旧扱いしない。 */
public enum ModuleEventRecoveryState {
    /** 発行対象が未 close、または業務反映が未完了。 */
    OPEN,
    /** 発行対象を close し、全対象の実業務反映を確認済み。 */
    CLOSED_COMPLETE,
    /** 未接続、証明不能、または当該 episode を確認できない。 */
    UNKNOWN
}
