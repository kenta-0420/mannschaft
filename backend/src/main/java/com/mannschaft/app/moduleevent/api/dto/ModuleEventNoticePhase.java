package com.mannschaft.app.moduleevent.api.dto;

/** 通知する状態遷移。短期 retry や手動再開受付は独立した通知 phase にしない。 */
public enum ModuleEventNoticePhase {
    DELAYED,
    QUARANTINED,
    RECOVERED
}
