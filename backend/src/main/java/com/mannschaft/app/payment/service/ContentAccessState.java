package com.mannschaft.app.payment.service;

/** F08.9 Phase 1 の可視性×会費ゲート合成結果。 */
public enum ContentAccessState {
    /** 可視性・課金の双方を満たし、完全な内容を返せる。 */
    FULL,
    /** 可視性は満たすが未払い。タイトルと最小限のロック情報のみ返せる。 */
    LOCKED,
    /** 可視性または存在を満たさず、内容を返してはならない。 */
    HIDDEN
}
