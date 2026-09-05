package com.mannschaft.app.common.architecture.fixtures.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.transaction.annotation.Transactional;

/**
 * {@code TransactionalTestNotificationObservationGuardTest} の検体（負例・正例）。
 *
 * <p>クラスに {@code @Transactional} を付けることで「実効的にトランザクショナルなテスト」を模す。
 * JUnit の注釈（{@code @Test} 等）は<b>付けない</b>ので、このクラス自体はテストとして実行されない
 * （検体であって試験ではない）。</p>
 *
 * <p>メソッド名は<b>必ず ASCII</b> にすること。変異テストが正規表現でメソッド本体を差し替えるため。</p>
 *
 * <p>本ファイルは番人の走査対象外パッケージ
 * （{@code com.mannschaft.app.common.architecture} 配下）に置いてある。
 * ここに検体として置いた違反が本番走査で拾われてしまうと、番人が常に赤になるためである。
 * 検体に対する判定は {@code TransactionalTestNotificationObservationGuardConditionTest} が行う。</p>
 */
@Transactional
public class TransactionalNotificationObservationFixture {

    private long notificationRowCount;
    private final Object notificationService = new Object();

    /**
     * 負例1: 生 SQL で {@code notifications} テーブルを数え「0 件であること」を検証する。
     * L8 で実在した {@code ScheduleKeepConvertContractIT} の是正前と同型。
     */
    public void negativeRawSqlZeroAssertion() {
        long count = query("SELECT COUNT(*) FROM notifications WHERE user_id = 1");
        assertThat(count).isZero();
    }

    /** 負例2: {@code notification_type} 列で絞った件数を検証する。 */
    public void negativeNotificationTypeColumn() {
        long count = query("SELECT COUNT(*) FROM notifications WHERE notification_type = 'SCHEDULE_REMINDER'");
        assertThat(count).isEqualTo(1L);
    }

    /** 負例3: {@code countNotifications} ヘルパ経由で数える。 */
    public void negativeCountHelper() {
        assertThat(countNotifications(1L)).isZero();
    }

    /** 負例4: 通知コラボレータのモックを {@code verify(..., never())} で検証する。 */
    public void negativeVerifyNeverOnCollaborator() {
        verify(notificationService, never());
    }

    /** 正例1: 通知行を投入するだけ（配送の観測ではない）。 */
    public void positiveSeedOnly() {
        new NotificationFixtureStubs.RepositoryStub().save(new Object());
    }

    /** 正例2: 業務側の結果だけを検証する。 */
    public void positiveBusinessAssertionOnly() {
        assertThat(notificationRowCount).isNotNegative();
    }

    // --- 以下は検体を compile させるためだけのスタブ（判定には関与しない） ---

    private long query(String sql) {
        return sql.length() > 0 ? notificationRowCount : 0L;
    }

    private long countNotifications(Long userId) {
        return userId == null ? 0L : notificationRowCount;
    }

    private void verify(Object mock, Object mode) {
        // no-op
    }

    private Object never() {
        return null;
    }
}
