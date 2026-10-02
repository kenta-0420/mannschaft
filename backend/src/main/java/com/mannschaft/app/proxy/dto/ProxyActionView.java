package com.mannschaft.app.proxy.dto;

import java.time.LocalDateTime;

/**
 * 代理入力記録の読み取りビュー（F14.1）。
 *
 * <p>{@code proxy_input_records} の {@link com.mannschaft.app.proxy.entity.ProxyInputRecordEntity} を
 * ドメイン外へ漏らさずに引き渡すための軽量プリミティブ DTO。他ドメイン（例: auth の
 * {@code GuardianChildViewService}）は {@link com.mannschaft.app.proxy.service.ProxyInputQueryService}
 * 経由で本ビューを受け取り、Entity/Repository を直接参照しない（ドメイン境界・ArchUnit D-1/D-3）。</p>
 *
 * @param id               代理入力記録 ID
 * @param subjectUserId    本人（代理された側）のユーザー ID
 * @param proxyUserId      代理者（代わりに操作した人）のユーザー ID
 * @param featureScope     操作対象機能スコープ（例: SCHEDULE_ATTENDANCE / PAYMENT）
 * @param targetEntityType 操作対象エンティティ種別（例: SCHEDULE_ATTENDANCE）
 * @param targetEntityId   操作対象レコード ID
 * @param inputSource      入力元（PAPER_FORM / PHONE_INTERVIEW / IN_PERSON / GUARDIANSHIP_SWITCH）
 * @param createdAt        作成日時
 */
public record ProxyActionView(
        Long id,
        Long subjectUserId,
        Long proxyUserId,
        String featureScope,
        String targetEntityType,
        Long targetEntityId,
        String inputSource,
        LocalDateTime createdAt) {
}
