package com.mannschaft.app.common.architecture.fixtures.alic;

/**
 * fixture（AC-F02 負例）: 正規経路以外（通常 API のコントローラー等）から委任入口を呼ぶダミー。
 * 規則はこの辺を違反として検出しなければならない。委任でない入口（readDirect）の呼び出しは対象外。
 */
public class AlicRogueDelegatedCaller {

    private final AlicDelegatedTarget target = new AlicDelegatedTarget();

    public String detail(Long id, Long viewer) {
        target.readDirect(id);
        return target.readDelegated(id, viewer);
    }
}
