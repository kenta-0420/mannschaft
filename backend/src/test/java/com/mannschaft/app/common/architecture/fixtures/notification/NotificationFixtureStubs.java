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

        /**
         * 語彙の外にある API。メソッド参照ゲートの変異テストで使う。
         *
         * <p>「レシーバの型がその名前の API を宣言している」という条件だけでは通してしまう形を
         * 語彙の完全一致が落としていることを確かめるため、<b>宣言はされているが語彙に一致しない</b>
         * 名前が必要になる。
         */
        public Object toResponse(Object... args) {
            return null;
        }
    }

    /** {@code NotificationDeliveryRunner} 相当（{@code sendOne} を持つ配送 Runner）。 */
    public static class RunnerStub {
        public Object sendOne(Object request) {
            return null;
        }
    }

    /**
     * 命名規約から外れた通知 API を持つゲートウェイ相当。
     *
     * <p>番人は綴り（{@code notify*} / {@code sendOne} 等）で通知発火を判定するため、
     * こういう命名は<b>綴りからは</b>見えない。Issue #3039 以降は
     * 「委譲先の型が実際に通知を発火するか」という型の軸で捕まえるので、
     * <b>中で本当に通知しているものだけ</b>が検出される。したがってこのスタブの
     * {@code send} / {@code publishNotification} / {@code enqueue} は実際に通知を発火させ、
     * 逆に {@code createNotificationCreditCheckoutSession} は発火させない（＝検出されないのが正しい）。
     */
    public static class GatewayStub {
        private final HelperStub notificationHelper = new HelperStub();

        /** 綴りは通知に見えないが、中では実際に通知を発火している。 */
        public void send(Object... args) {
            notificationHelper.notify(args);
        }

        /** 同上（{@code publish*} 綴り）。 */
        public void publishNotification(Object... args) {
            notificationHelper.notify(args);
        }

        /** 同上（{@code enqueue*} 綴り）。1段の同一クラス内委譲を挟む。 */
        public void enqueue(Object... args) {
            send(args);
        }

        /**
         * {@code StripePaymentProvider#createNotificationCreditCheckoutSession} 相当。
         *
         * <p>綴りが {@code createNotification} で始まるだけの<b>決済</b>API であり通知ではない。
         */
        public String createNotificationCreditCheckoutSession(Object... args) {
            return "";
        }
    }

    /** 別 Bean へ委譲された先で通知するワーカー相当（Issue #3039 以降は1ホップだけ追える）。 */
    public static class WorkerStub {
        private final HelperStub notificationHelper = new HelperStub();

        /** {@code @Transactional} は無いが、呼び出し元の業務TXにそのまま参加する。 */
        public void send(Long userId) {
            notificationHelper.notify(userId, "TYPE", "件名", "本文");
        }
    }

    /**
     * {@link WorkerStub} と<b>同じメソッド名を持つが通知しない</b>ワーカー相当。
     *
     * <p>ローカル変数がフィールドをシャドーイングする検体（Codex 独立検分 条件2）で使う。
     * 「どちらの型で判定するか」によって結論が反転する状況を作るのが目的なので、
     * {@code send} という綴りは {@link WorkerStub} と揃えたうえで中身は通知しない。
     */
    public static class SilentWorkerStub {
        public void send(Long userId) {
            // 通知しない。検体の対照として存在する。
        }
    }

    /** 業務側リポジトリ相当（業務TXが実在することを形として示すためだけのもの）。 */
    public static class RepositoryStub {
        public void save(Object entity) {
            // 検体用スタブ。実処理は持たない。
        }
    }
}
