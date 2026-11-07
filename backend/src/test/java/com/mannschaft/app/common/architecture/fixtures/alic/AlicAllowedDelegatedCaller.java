package com.mannschaft.app.common.architecture.fixtures.alic;

/**
 * fixture（AC-F02 対照）: 委任入口を呼んでよい正規経路のダミー
 * （本番の {@code AnnouncementPreviewSourceService} に相当）。規則はこれを違反にしてはならない。
 */
public class AlicAllowedDelegatedCaller {

    private final AlicDelegatedTarget target = new AlicDelegatedTarget();

    public String preview(Long id, Long viewer) {
        return target.readDelegated(id, viewer);
    }
}
