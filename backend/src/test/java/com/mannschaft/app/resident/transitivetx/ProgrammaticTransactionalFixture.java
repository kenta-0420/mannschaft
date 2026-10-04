package com.mannschaft.app.resident.transitivetx;

import com.mannschaft.app.proxy.repository.TransitiveProxyRepository;
import com.mannschaft.app.resident.repository.TransitiveResidentRepository;

import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** D-3 の明示トランザクション境界を実バイトコードで検証する fixture。実行はしない。 */
public class ProgrammaticTransactionalFixture {

    /** インターフェース経由の明示実行と越境依存。 */
    public static class InterfaceExecution {
        private final TransactionOperations transactions = null;
        private final TransitiveProxyRepository repository = new TransitiveProxyRepository();

        public void run() {
            transactions.execute(status -> {
                repository.save();
                return null;
            });
        }
    }

    /** Template の戻り値なし実行と越境依存。 */
    public static class TemplateExecutionWithoutResult {
        private final TransactionTemplate transactions = null;
        private final TransitiveProxyRepository repository = new TransitiveProxyRepository();

        public void run() {
            transactions.executeWithoutResult(status -> repository.save());
        }
    }

    /** Template を保持して設定するだけではトランザクション実行とはみなさない。 */
    public static class UnusedTemplate {
        private final TransactionTemplate transactions = null;
        private final TransitiveProxyRepository repository = new TransitiveProxyRepository();

        public void run() {
            transactions.setReadOnly(true);
            repository.save();
        }
    }

    /** 明示実行しても同一ドメインの Repository は許可される。 */
    public static class SameDomainExecution {
        private final TransactionTemplate transactions = null;
        private final TransitiveResidentRepository repository = new TransitiveResidentRepository();

        public void run() {
            transactions.executeWithoutResult(status -> repository.save());
        }
    }
}
