package com.mannschaft.app.common.architecture.fixtures;

/**
 * 認可を委譲される薄いドメインサービスのダミー（メタテスト用 fixture）。
 *
 * <p>クラス名は {@code *Service} であり <b>認可クラスではない</b>
 * （{@code *AccessService}/{@code *AccessGuard} で終わらない）。
 * したがってこのサービスへの呼び出し自体は認可シグナルにならない。
 * 認可シグナルは、このサービス内部の private helper がさらに
 * {@link DummyAccessGuard} を呼ぶ「深さ2」の委譲でのみ成立する。
 */
public class DummyDelegateService {

    private final DummyAccessGuard accessGuard = new DummyAccessGuard();

    /**
     * Controller から呼ばれる公開メソッド（深さ1）。
     * 直接は認可クラスを呼ばず、private helper に委譲する。
     */
    public void doWork(Long scopeId) {
        enforceAuthorization(scopeId);
    }

    /**
     * 同一クラス内 private helper（深さ2）。ここで初めて認可クラスを直接呼ぶ。
     * D=2 の BFS 探索でのみ到達できる。
     */
    private void enforceAuthorization(Long scopeId) {
        accessGuard.checkAccess(scopeId);
    }
}
