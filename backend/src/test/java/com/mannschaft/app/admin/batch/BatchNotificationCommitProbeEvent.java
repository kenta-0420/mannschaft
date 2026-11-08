package com.mannschaft.app.admin.batch;

/** {@link BatchNotificationTransactionIT} の commit 直前 rollback 注入（CommitSaboteur）の対象にするイベント。 */
record BatchNotificationCommitProbeEvent(String tag) {
}
