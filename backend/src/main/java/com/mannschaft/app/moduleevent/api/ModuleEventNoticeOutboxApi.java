package com.mannschaft.app.moduleevent.api;

import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.notice.ModuleEventImpactEvaluator;
import com.mannschaft.app.moduleevent.notice.ModuleEventNoticeReadService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * notification から一方向に呼ぶ公開窓口。HTTP・Entity・通知内部型を公開しない。
 * 現在は実 DB assertion red 待ちの読取骨格であり、動作実装ではない。
 * caller を NOT_SUPPORTED で停止し、M の独立読取終了後だけ業務 SPI を呼ぶ段取りを担う。
 */
@Component
public final class ModuleEventNoticeOutboxApi {
    private final ModuleEventNoticeReadService reads;
    private final ModuleEventImpactEvaluator evaluator;
    private final TransactionTemplate withoutCallerTransaction;

    public ModuleEventNoticeOutboxApi(ModuleEventNoticeReadService reads, ModuleEventImpactEvaluator evaluator,
            PlatformTransactionManager transactions) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        withoutCallerTransaction = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        withoutCallerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    /** 不変 descriptor の読取。現在の配送許可を証明する戻り値ではない。 */
    public Optional<ModuleEventNoticeDescriptor> readDescriptor(long noticeRef) {
        return Optional.empty();
    }

    /** 未評価・不正版・不適格 status は empty、正常な受信者0件と旧 phase 終端は of(emptyList)。 */
    public Optional<List<ModuleEventNoticeRecipient>> nextRecipients(long noticeRef, long afterUserId, int limit) {
        return Optional.empty();
    }

    /** 有界候補を現在再検査する。業務 close 評価後も別 M 読取で pending / key / status を再確認する。 */
    public Optional<List<ModuleEventNoticeRecipient>> eligibleRecipients(long noticeRef, List<Long> candidateUserIds) {
        return Optional.empty();
    }
}
