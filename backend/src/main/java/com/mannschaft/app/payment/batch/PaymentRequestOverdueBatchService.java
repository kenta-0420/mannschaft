package com.mannschaft.app.payment.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * F08.9 P7 第二波: 協会請求の期限超過バッチ（OVERDUE 遷移・02_api §7）。
 *
 * <p>支払期限（{@code due_date}）が当日より前で、まだ {@code SENT}/{@code VIEWED} の請求を {@code OVERDUE} へ
 * 遷移させる。OVERDUE でも支払いは可能（{@link com.mannschaft.app.payment.service.PaymentRequestService#pay}）。
 * {@code PAID}/{@code CANCELLED}/{@code DRAFT} は対象外（抽出クエリで SENT/VIEWED に絞り、Entity 側でも
 * {@link PaymentRequestEntity#markAsOverdueIfDue} が二重防御する）。</p>
 *
 * <h3>抽出と境界</h3>
 * <p>{@code idx_pr_due (status, due_date)} で {@code due_date < today} を {@link Slice} ページングで走査し、
 * 全件メモリ展開を避ける。1 ページずつ遷移・保存し、次ページが無くなるまで（または上限ページまで）繰り返す。</p>
 *
 * <h3>スケジュール</h3>
 * <p>毎日 23:30 JST。{@code @SchedulerLock} で多重起動を防ぎ、{@link Clock} 注入で date-pin テスト可能。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRequestOverdueBatchService {

    /** 1 ページあたりの抽出件数。 */
    static final int PAGE_SIZE = 500;

    /** 全件走査の暴走を防ぐ最大ページ数（500 件 × 200 ページ = 10 万件／回）。 */
    static final int MAX_PAGES = 200;

    /** 対象状態（SENT/VIEWED のみ・PAID/CANCELLED/DRAFT/OVERDUE は対象外）。 */
    private static final Set<PaymentRequestStatus> TARGET_STATUSES =
            Set.of(PaymentRequestStatus.SENT, PaymentRequestStatus.VIEWED);

    private final PaymentRequestRepository paymentRequestRepository;
    private final Clock clock;

    /**
     * 期限超過バッチ。毎日 23:30 JST に実行する。
     */
    @BatchEndpoint(name = "payment-request-overdue-batch",
            description = "協会請求 期限超過（SENT/VIEWED→OVERDUE）バッチ")
    @Scheduled(cron = "0 30 23 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "paymentRequestOverdueBatch", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    @Transactional
    public void execute() {
        LocalDate today = LocalDate.now(clock);
        log.info("協会請求 期限超過バッチ開始: today={}（due_date < today を OVERDUE 化）", today);

        int transitioned = 0;
        // due_date 昇順で安定して走査する（境界付き Slice）。常に先頭ページを取り直す
        // （遷移で対象から外れていくため page=0 固定で hasNext が尽きるまで回す）。
        Sort sort = Sort.by(Sort.Direction.ASC, "dueDate");
        for (int page = 0; page < MAX_PAGES; page++) {
            Slice<PaymentRequestEntity> slice = paymentRequestRepository
                    .findByStatusInAndDueDateLessThanAndDeletedAtIsNull(
                            TARGET_STATUSES, today, PageRequest.of(0, PAGE_SIZE, sort));
            List<PaymentRequestEntity> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            for (PaymentRequestEntity request : batch) {
                if (request.markAsOverdueIfDue()) {
                    paymentRequestRepository.save(request);
                    transitioned++;
                }
            }
            // 遷移済みは次回抽出から外れるため、対象が尽きれば slice は空になる。
            // 念のため hasNext も見るが、本ループは「空になったら break」を主条件にする。
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }

        log.info("協会請求 期限超過バッチ完了: OVERDUE 遷移={}件", transitioned);
    }
}
