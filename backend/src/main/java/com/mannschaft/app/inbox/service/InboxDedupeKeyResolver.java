package com.mannschaft.app.inbox.service;

import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * F04.11 統合通知インボックス：名寄せ（重複統合）キー解決器（Phase 3 ①）。
 *
 * <p>各ソース通知が指す<b>終端実体</b>（例: 同じブログ記事 BLOG_POST:123）を正規化キーへ写像する。
 * NOTIFICATION の {@code sourceType}、ANNOUNCEMENT の終端 {@code sourceType}、MENTION の {@code targetType}
 * を {@link NotificationSourceTypeMapper#resolve(String)} で {@link ReferenceType} に解決し、
 * {@code "{ReferenceType}:{terminalId}"} を canonicalRef とする。集約サービスはこの canonicalRef で
 * グルーピングし、2 件以上のみ 1 代表へ畳む（設計書: 03_business_logic.md §8）。</p>
 *
 * <p><b>誤突合の安全弁（最高リスク領域）</b>: 誤って別実体を畳むと ADHD ユーザーが「片方だけ既読/
 * アーカイブ」で深刻に混乱する。本解決器は次を厳守する:
 * <ul>
 *   <li>正規化に<b>成功した場合のみ</b> canonicalRef を返す（{@link #resolveCanonicalKey} が present）。</li>
 *   <li>{@code sourceType} が {@link ReferenceType} に未マッピング、または終端 ID が null の場合は
 *       正規化不能とみなし {@link Optional#empty()} を返す。呼び出し側はこの場合
 *       <b>自分自身キー</b>（{@code "{InboxSourceType}:{sourceId}"}）へフォールバックし、決して他項目と畳まない。</li>
 *   <li>同一 ReferenceType でも終端 ID が異なれば別キー（異実体は畳まれない）。</li>
 * </ul>
 * これにより「正規化成功かつ同一 EntityRef」のときに限り畳み込みが起きる。</p>
 */
@Component
public class InboxDedupeKeyResolver {

    /**
     * 終端 {@code (sourceType, terminalId)} を正規化キー {@code "{ReferenceType}:{terminalId}"} に解決する。
     *
     * <p>正規化に成功するのは {@code sourceType} が {@link ReferenceType} にマッピング済み、かつ
     * {@code terminalId} が非 null の場合のみ。いずれかを満たさなければ {@link Optional#empty()}。</p>
     *
     * @param terminalSourceType 終端実体の種別文字列（notifications.sourceType / mentions.targetType /
     *                           announcement_feeds.sourceType など。null 可）
     * @param terminalId         終端実体の ID（null 可）
     * @return 正規化キー。正規化不能なら空
     */
    public Optional<String> resolveCanonicalKey(String terminalSourceType, Long terminalId) {
        if (terminalId == null) {
            return Optional.empty();
        }
        return NotificationSourceTypeMapper.resolve(terminalSourceType)
                .map(refType -> refType.name() + ":" + terminalId);
    }

    /**
     * 正規化キーを返す。正規化不能な場合は呼び出し側が用意した<b>自分自身キー</b>へフォールバックする。
     *
     * @param terminalSourceType 終端実体の種別文字列（null 可）
     * @param terminalId         終端実体の ID（null 可）
     * @param selfKey            正規化不能時のフォールバック（{@code "{InboxSourceType}:{sourceId}"}＝
     *                           当該項目に固有・他項目と決して衝突しない）
     * @return 正規化キーまたは自分自身キー
     */
    public String canonicalRefOrSelf(String terminalSourceType, Long terminalId, String selfKey) {
        return resolveCanonicalKey(terminalSourceType, terminalId).orElse(selfKey);
    }
}
