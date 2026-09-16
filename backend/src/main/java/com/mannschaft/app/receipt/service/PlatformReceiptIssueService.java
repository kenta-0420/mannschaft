package com.mannschaft.app.receipt.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.receipt.ReceiptErrorCode;
import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.ReceiptScopes;
import com.mannschaft.app.receipt.dto.PlatformReceiptIssueCommand;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import com.mannschaft.app.receipt.repository.ReceiptIssuerSettingsRepository;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 運営領収書の発行サービス（F08.12 §3.1「冪等をどの層で担保するか」/ §5.2）。
 *
 * <h2>すべての発行経路が通る唯一の入口</h2>
 * <p>発行契機は広告費・通知クレジット・（将来）サブスクの 3 系統に加え、運用リカバリの
 * 手動補填がある。<b>各ドメインの webhook 側で個別に冪等を書くと、収入源が増えるたびに
 * 書き漏れる。</b>そこで発行経路をすべて {@link #issueFor} に集約し、冪等はここ 1 箇所で担保する。
 * 手動補填も別経路を作らず同じメソッドを通す。</p>
 *
 * <h2>冪等の役割分担（DB 制約が主・アプリ層の検査が従）</h2>
 * <p>「発行前に存在確認して無ければ作る」だけでは TOCTOU であり、webhook の重複配送や
 * 「webhook と手動補填」の並行実行で 2 通作れてしまう。金銭の証憑が二重に出る事故になる。
 * <b>正しさは {@code uq_r_active_platform_source}（STORED 生成列 + UNIQUE）が保証する。</b>
 * 先に読むのは、正常系（発行済み source への再要求）で毎回 DB 例外を出してログを汚し
 * トランザクションをロールバックさせないための<b>速度と可読性のため</b>である。</p>
 *
 * <p>この構えは {@code WebhookIdempotencyService#tryBegin} に倣ったものであり、
 * 「アプリ層の冪等は best-effort、DB 制約が最終 backstop」という本リポの既存の立場に揃う。
 * なお {@code WebhookIdempotencyService} は {@code event_id}（Stripe の配送単位）でゲート
 * するため、同じ webhook の重複配送は防げるが「webhook と手動補填」「別々の event_id で
 * 届いた同一請求書への 2 回の paid」は防げない。重複の単位が違うので両者は排他ではなく
 * 重ねて使う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformReceiptIssueService {

    private final ReceiptRepository receiptRepository;
    private final ReceiptIssuerSettingsRepository issuerSettingsRepository;
    private final ReceiptNumberSequenceService numberSequenceService;

    /**
     * 発行結果。既存を返した場合は {@code newlyIssued=false}。
     *
     * @param receipt     領収書
     * @param newlyIssued 本呼び出しで新規発行されたか（冪等な成功と区別するため）
     */
    public record IssueResult(ReceiptEntity receipt, boolean newlyIssued) {
    }

    /**
     * 元データに対する運営領収書を発行する（冪等）。
     *
     * <p>既に有効な領収書があれば<b>それを返す</b>。重複は例外ではなく冪等な成功である。</p>
     *
     * <p>トランザクションは {@code REQUIRES_NEW}。入金確定のトランザクションに参加すると、
     * 領収書の発行失敗が入金確定をロールバックしてしまう（§5.2）。</p>
     *
     * @param command 発行指示
     * @return 発行結果
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IssueResult issueFor(PlatformReceiptIssueCommand command) {
        if (command.sourceType() == null || command.sourceRef() == null) {
            // PLATFORM 領収書が source を持たないと、その 1 件だけ取引先で検索できなくなる
            // （電子帳簿保存法の検索 3 要件。§4.1）。DB の CHECK 制約と二重で塞ぐ。
            throw new BusinessException(ReceiptErrorCode.PLATFORM_SOURCE_REQUIRED);
        }

        // 1) 先に軽い存在確認。防止の主体ではない（正しさは UNIQUE 制約が保証する）。
        Optional<ReceiptEntity> existing = receiptRepository.findActiveBySource(
                ReceiptScopeType.PLATFORM, command.sourceType(), command.sourceRef().value());
        if (existing.isPresent()) {
            return new IssueResult(existing.get(), false);
        }

        try {
            return new IssueResult(persist(command), true);
        } catch (DataIntegrityViolationException e) {
            // 2) 並行発行で UNIQUE 競合。症状を隠さず情報ログに残したうえで勝者を読み直す。
            log.info("運営領収書が並行発行と競合したため既存を返す sourceType={} sourceRef={}",
                    command.sourceType(), command.sourceRef(), e);
            ReceiptEntity winner = receiptRepository.findActiveBySource(
                            ReceiptScopeType.PLATFORM, command.sourceType(), command.sourceRef().value())
                    .orElseThrow(() -> e);
            return new IssueResult(winner, false);
        }
    }

    /** 領収書行を作って保存する。番号は採番専用表から払い出す（発行者設定行はロックしない）。 */
    private ReceiptEntity persist(PlatformReceiptIssueCommand command) {
        ReceiptIssuerSettingsEntity settings = issuerSettingsRepository
                .findByScopeTypeAndScopeId(ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID)
                .orElseThrow(() -> new BusinessException(ReceiptErrorCode.PLATFORM_SETTINGS_NOT_FOUND));

        // 発行者は「システム自動発行」を表すシステムユーザー（V1.012 seed。id=1）に固定する。
        // 実在の運営者個人を割り当てると、実際には操作していない人物が法的文書の発行者として
        // 記録され続けるうえ、監査時に人が出したのか自動発行かを区別できなくなる。
        Long issuedBy = SystemUsers.SYSTEM_USER_ID;

        String periodKey = ReceiptNumberSequenceService.periodKeyOf(command.paymentDate());
        int number = numberSequenceService.reserveRange(
                ReceiptScopeType.PLATFORM, ReceiptScopes.PLATFORM_SCOPE_ID, periodKey, 1);

        boolean qualified = Boolean.TRUE.equals(settings.getIsQualifiedInvoicer())
                && settings.getInvoiceRegistrationNumber() != null;

        ReceiptEntity receipt = ReceiptEntity.builder()
                .scopeType(ReceiptScopeType.PLATFORM)
                .scopeId(ReceiptScopes.PLATFORM_SCOPE_ID)
                .receiptNumber(ReceiptNumberSequenceService.formatPlatformNumber(periodKey, number))
                .sourceType(command.sourceType())
                .sourceRef(command.sourceRef().value())
                .recipientUserId(command.recipientUserId())
                .recipientName(command.recipientName())
                // 発行者情報は発行時点のスナップショット（§5.5）。設定を後から変えても過去の証憑は動かない。
                .issuerName(settings.getIssuerName())
                .issuerPostalCode(settings.getPostalCode())
                .issuerAddress(settings.getAddress())
                .issuerPhone(settings.getPhone())
                .isQualifiedInvoice(qualified)
                .invoiceRegistrationNumber(qualified ? settings.getInvoiceRegistrationNumber() : null)
                .description(command.description())
                .amount(command.amount())
                .taxRate(command.taxRate() == null ? new BigDecimal("10.00") : command.taxRate())
                .taxAmount(command.taxAmount())
                .amountExclTax(command.amountExclTax())
                .paymentMethodLabel(command.paymentMethodLabel())
                .paymentDate(command.paymentDate())
                .issuedBy(issuedBy)
                .build();

        return receiptRepository.saveAndFlush(receipt);
    }
}
