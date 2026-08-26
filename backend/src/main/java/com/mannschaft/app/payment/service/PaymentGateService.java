package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F08.9 P4: ペイウォール（受益者キー判定）サービス（設計書 F08.9 02 §6 / 03_security §4 / README §5）。
 *
 * <p>コンテンツ（{@code contentType}, {@code contentId}）に紐づく {@code content_payment_gates} の
 * payment_item すべてについて、<b>閲覧者本人（viewer）の支払い状態のみ</b>を
 * {@link MemberPaymentRepository#existsValidPaidPayment(Long, Long)} で評価する。
 * 「誰が払ったか」ではなく「閲覧者＝受益者に有効な支払いがあるか」で判定するため、
 * 他人の支払いで解錠されることはない（受益者キー判定・03_security §4）。</p>
 *
 * <h3>判定ロジック</h3>
 * <ul>
 *   <li><b>ゲートなし</b> → {@code accessible=true}（誰でも閲覧可・ペイウォール非対象）。</li>
 *   <li><b>全ゲート充足</b> → {@code accessible=true}。1つでも未充足 → {@code accessible=false}。</li>
 *   <li><b>titleHidden</b> → いずれかのゲートが {@code is_title_hidden=true} なら true（存在ごと秘匿・404相当）。</li>
 * </ul>
 *
 * <h3>fail-safe（03_security §4 — 漏洩より過剰遮断）</h3>
 * 判定不能（gate が参照する payment_item が消失・設定不整合）の場合は
 * <b>閲覧拒否側（{@code accessible=false}）に倒す</b>。例外は握りつぶさず、判定不能の理由をログに残す
 * （対処療法禁止・症状を隠さない）。
 *
 * <h3>titleHidden 時の存在秘匿</h3>
 * {@code titleHidden=true} のゲートを含むコンテンツでは、未払い者に対し
 * {@code requiredItems} の中身（名称・金額）も露出させない（空配列）。
 * is_title_hidden=true は「存在ごと秘匿」が原則のため、購入導線も出さない。
 *
 * <h3>本タスク（P4）のスコープ</h3>
 * 本サービスは「閲覧者自身の支払い状態」のみを判定する。
 * 可視性(visibility)との AND 連結（F00 {@code evaluateCustom} 経由）は後段 P4b の責務であり、
 * ここでは独自 visibility 述語を一切作らない（[[feedback_visibility_bypass_f00_audit]]）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentGateService {

    private final ContentPaymentGateRepository contentPaymentGateRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final PaymentItemRepository paymentItemRepository;

    /**
     * 指定コンテンツに対する閲覧者本人のペイウォール解錠可否を判定する（設計書 02 §6）。
     *
     * <p>受益者キー＝viewer 自身。viewer の支払い状態のみで判定し、他人の支払いでは解錠しない。</p>
     *
     * @param contentType  コンテンツ種別（POST/FILE/ANNOUNCEMENT/SCHEDULE 等）
     * @param contentId    コンテンツ ID
     * @param viewerUserId 閲覧者（＝受益者キー）のユーザー ID
     * @return ペイウォール判定結果（accessible / titleHidden / requiredItems）
     */
    public GateCheckResponse checkAccess(String contentType, Long contentId, Long viewerUserId) {
        List<ContentPaymentGateEntity> gates =
                contentPaymentGateRepository.findByContentTypeAndContentId(contentType, contentId);

        // ゲートなし = ペイウォール非対象 → 誰でも閲覧可
        if (gates.isEmpty()) {
            return new GateCheckResponse(true, false, List.of());
        }

        // タイトル秘匿はゲート設定の OR（1つでも秘匿なら存在ごと秘匿）
        boolean titleHidden = gates.stream()
                .anyMatch(g -> Boolean.TRUE.equals(g.getIsTitleHidden()));

        boolean allSatisfied = true;
        List<GateCheckResponse.RequiredItem> requiredItems = new ArrayList<>();

        for (ContentPaymentGateEntity gate : gates) {
            Long itemId = gate.getPaymentItemId();

            // fail-safe: payment_item_id 欠落（設定不整合）→ 判定不能 → 閲覧拒否側へ倒す
            if (itemId == null) {
                log.warn("ペイウォール判定不能（payment_item_id 欠落）: contentType={}, contentId={}, gateId={} → accessible=false",
                        contentType, contentId, gate.getId());
                allSatisfied = false;
                continue;
            }

            // fail-safe: gate が参照する payment_item が消失（ゲート設定不整合）→ 判定不能 → 閲覧拒否側へ倒す
            Optional<PaymentItemEntity> itemOpt = paymentItemRepository.findById(itemId);
            if (itemOpt.isEmpty()) {
                log.warn("ペイウォール判定不能（payment_item 消失）: contentType={}, contentId={}, paymentItemId={} → accessible=false",
                        contentType, contentId, itemId);
                allSatisfied = false;
                continue;
            }
            PaymentItemEntity item = itemOpt.get();

            // 受益者キー判定: 閲覧者本人の有効な PAID レコードのみで解錠（他人の支払いで解錠しない）
            boolean satisfied = memberPaymentRepository.existsValidPaidPayment(viewerUserId, itemId);
            if (!satisfied) {
                allSatisfied = false;
            }

            requiredItems.add(new GateCheckResponse.RequiredItem(
                    itemId, item.getName(), item.getAmount(), satisfied));
        }

        // titleHidden=true は存在ごと秘匿 → requiredItems の中身（名称・金額）も露出させない
        List<GateCheckResponse.RequiredItem> exposedItems = titleHidden ? List.of() : requiredItems;

        return new GateCheckResponse(allSatisfied, titleHidden, exposedItems);
    }

    /**
     * 指定コンテンツにペイウォールゲートが 1 件以上設定されているかを返す。
     *
     * <p>{@link #checkAccess} が予期せぬ例外で判定不能に陥った際の <b>fail-closed 判定</b>
     * （ゲート有り＝本文をマスク／ゲート無し＝非課金コンテンツゆえ従来どおり本文を返す）に用いる。
     * 本文取得経路（cms/publicview）が「評価不能の真因がゲート不在かどうか」を切り分けるための
     * 軽量な存在確認であり、支払い状態は評価しない。</p>
     *
     * @param contentType コンテンツ種別
     * @param contentId   コンテンツ ID
     * @return ゲートが 1 件以上設定されていれば true
     */
    public boolean hasGate(String contentType, Long contentId) {
        return !contentPaymentGateRepository
                .findByContentTypeAndContentId(contentType, contentId).isEmpty();
    }
}
