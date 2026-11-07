package com.mannschaft.app.cms.service;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Auth の行ロック TX を保持したまま CMS の公開処理だけを独立 TX で実行する。 */
@Service
@RequiredArgsConstructor
class BlogRanchCmsTransactionBoundary {
    private final PlatformTransactionManager transactions;

    <T> T outsideAuthTransaction(Supplier<T> operation) {
        var boundary = new TransactionTemplate(transactions);
        boundary.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        return boundary.execute(ignored -> operation.get());
    }
}
