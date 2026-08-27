package com.mannschaft.app.common.architecture.fixtures.notification;

/**
 * 通知番人（{@code NotificationTransactionBoundaryGuardTest}）の検体 fixture が使うスタブ群。
 *
 * <p>fixture は「番人が検出すべき／してはならない構文の形」を再現するためだけに存在する。
 * 本番の Bean を参照すると fixture がドメインの都合に振り回されるため、呼び先はここで完結させる。
 *
 * <p><b>Spring の Bean にしてはならない</b>（{@code @Service} / {@code @Component} を付けない）。
 * 付けるとコンポーネントスキャンに拾われ、{@code @SpringBootTest} 系の IT が壊れる。
 *
 * <p>番人本体は {@code src/main/java} のみを走査するため、これらの fixture が本番の検出結果や
 * 凍結リストへ混入することはない。
 */
public final class NotificationFixtureStubs {

    private NotificationFixtureStubs() {
    }

    /** {@code NotificationHelper} 相当。呼び出しの綴りだけを再現する。 */
    public static class HelperStub {
        public void notify(Object... args) {
            // 検体用スタブ。実処理は持たない。
        }

        public void notifyAllPreAuthorized(Object... args) {
            // 検体用スタブ。実処理は持たない。
        }
    }

    /** {@code NotificationDeliveryRunner} 相当（{@code sendOne} を持つ配送 Runner）。 */
    public static class RunnerStub {
        public Object sendOne(Object request) {
            return null;
        }
    }

    /** 業務側リポジトリ相当（業務TXが実在することを形として示すためだけのもの）。 */
    public static class RepositoryStub {
        public void save(Object entity) {
            // 検体用スタブ。実処理は持たない。
        }
    }
}
