package com.mannschaft.app.auth.service;

import org.testcontainers.containers.MySQLContainer;
import com.mannschaft.app.common.BusinessException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** 専用テストコンテナの対象users行待ちだけを観測する。DB設定変更・値出力はしない。 */
final class BirthProfileLockWaitObserver {
    private BirthProfileLockWaitObserver() {}

    private static AssertionError completedBeforeWait(Future<?> worker) {
        try {
            worker.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            String type = cause == null ? "NONE" : cause.getClass().getName();
            String code = cause instanceof BusinessException business
                    ? business.getErrorCode().getCode() : "NONE";
            // 例外message/causeは入力値を含み得るため保持せず、classと業務codeのみ残す。
            return new AssertionError("users待機前にworkerが失敗 class=" + type + " code=" + code);
        } catch (CancellationException failure) {
            return new AssertionError("users待機前にworkerが取消されました");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new AssertionError("users待機前worker原因の回収が中断されました");
        }
        return new AssertionError("対象users行の待機へ到達する前にworkerが正常終了しました");
    }

    static void awaitUserWait(MySQLContainer<?> mysql, Long userId, Future<?> worker) {
        String url = mysql.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?")
                + "connectTimeout=1000&socketTimeout=1000&useSSL=false&allowPublicKeyRetrieval=true";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // この資格情報はMYSQL fixtureの値のみ。実アプリのDataSource・秘密を参照しない。
        try (var observer = DriverManager.getConnection(url, "root", mysql.getPassword());
             var query = observer.prepareStatement("""
                     SELECT COUNT(*)
                     FROM performance_schema.data_lock_waits waits
                     JOIN performance_schema.data_locks requested
                       ON requested.ENGINE = waits.ENGINE
                      AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                     WHERE requested.OBJECT_SCHEMA = ? AND requested.OBJECT_NAME = 'users'
                       AND requested.INDEX_NAME = 'PRIMARY' AND requested.LOCK_TYPE = 'RECORD'
                       AND requested.LOCK_DATA = ?
                     """)) {
            query.setQueryTimeout(1);
            query.setString(1, mysql.getDatabaseName());
            query.setString(2, userId.toString());
            while (System.nanoTime() < deadline) {
                try (var rows = query.executeQuery()) {
                    if (rows.next() && rows.getLong(1) > 0) return;
                }
                if (worker.isDone()) throw completedBeforeWait(worker);
                Thread.sleep(25);
            }
            throw new AssertionError("5秒以内に対象users行の実ロック待機を観測できませんでした");
        } catch (SQLException failure) {
            // JDBC例外本文には接続情報が含まれ得るため固定説明だけを出す。
            throw new AssertionError("専用MySQLコンテナのロック観測接続またはSELECTに失敗しました");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("users行のロック観測が中断されました");
        }
    }
}
