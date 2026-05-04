package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F00 共通可視性判定ファサード。
 *
 * <p>各 {@link ContentVisibilityResolver} を {@link ReferenceType} 単位でディスパッチし、
 * 単発・バッチ・複数 type ミックスの判定を一元提供する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.4 完全一致。
 *
 * <p><strong>重要 (§15 D-13)</strong>: SystemAdmin 高速パスは <strong>本ファサードでは
 * 持たない</strong>。各 Resolver が実存確認込みの取得を行った後に SystemAdmin 判定を
 * 行うことで、「実在する ID だけを返す」セマンティクスを保ち IDOR 矛盾を回避する。
 *
 * <p><strong>fail-closed の原則 (§11.2)</strong>: 未対応 {@link ReferenceType} に対しては
 * {@link #canView} は false、{@link #filterAccessible} は空 Set を返す。
 * {@link #decide} は {@link DenyReason#UNSUPPORTED_REFERENCE_TYPE}、
 * {@link #assertCanView} は {@link BusinessException} をスローする。
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class ContentVisibilityChecker {

    private final Map<ReferenceType, ContentVisibilityResolver<?>> resolverMap;

    /**
     * Spring が {@link ContentVisibilityResolver} の全 Bean を List で渡す。
     * Constructor で {@link ContentVisibilityResolver#referenceType()} をキーとする
     * 不変 Map に変換する。同一 {@link ReferenceType} に対して複数の Resolver が
     * 登録された場合は {@link IllegalStateException} で起動失敗させる。
     *
     * @param resolvers Spring が収集した Resolver Bean の List (空でもよい)
     * @throws IllegalStateException referenceType が重複した場合
     */
    public ContentVisibilityChecker(List<ContentVisibilityResolver<?>> resolvers) {
        Map<ReferenceType, ContentVisibilityResolver<?>> map = new EnumMap<>(ReferenceType.class);
        for (ContentVisibilityResolver<?> resolver : resolvers) {
            ReferenceType type = resolver.referenceType();
            ContentVisibilityResolver<?> previous = map.putIfAbsent(type, resolver);
            if (previous != null) {
                throw new IllegalStateException(
                    "duplicate ContentVisibilityResolver for referenceType=" + type
                        + " (existing=" + previous.getClass().getName()
                        + ", duplicate=" + resolver.getClass().getName() + ")");
            }
        }
        this.resolverMap = Map.copyOf(map);
        log.info("ContentVisibilityChecker initialized with {} resolver(s): {}",
            this.resolverMap.size(), this.resolverMap.keySet());
    }

    /**
     * 単発判定。
     *
     * @param type      対象の reference_type
     * @param contentId 対象 contentId
     * @param userId    閲覧者 userId ({@code null} 可)
     * @return 閲覧可能なら true。未対応 type は fail-closed で false
     */
    public boolean canView(ReferenceType type, Long contentId, Long userId) {
        ContentVisibilityResolver<?> resolver = resolverMap.get(type);
        if (resolver == null) {
            recordUnsupported(type);
            return false;
        }
        return resolver.canView(contentId, userId);
    }

    /**
     * 同一 reference_type のバッチ判定。
     *
     * @param type   対象の reference_type
     * @param ids    判定対象の contentId 集合
     * @param userId 閲覧者 userId ({@code null} 可)
     * @return アクセス可能な contentId の Set。未対応 type は fail-closed で空 Set
     */
    public Set<Long> filterAccessible(
            ReferenceType type, Collection<Long> ids, Long userId) {
        ContentVisibilityResolver<?> resolver = resolverMap.get(type);
        if (resolver == null) {
            recordUnsupported(type);
            return Set.of();
        }
        return resolver.filterAccessible(ids, userId);
    }

    /**
     * 複数 reference_type の混在バッチ判定。
     *
     * <p>コルクボード等で複数種別の参照を一画面で表示する際に用いる。
     * 戻り値は入力で渡した type をすべてキーとして含み、未対応 type は空 Set にマップする。
     *
     * @param idsByType type ごとの contentId 集合
     * @param userId    閲覧者 userId ({@code null} 可)
     * @return type ごとのアクセス可能 Set
     */
    public Map<ReferenceType, Set<Long>> filterAccessibleByType(
            Map<ReferenceType, ? extends Collection<Long>> idsByType,
            Long userId) {
        Map<ReferenceType, Set<Long>> result = new EnumMap<>(ReferenceType.class);
        idsByType.forEach((type, ids) ->
            result.put(type, filterAccessible(type, ids, userId)));
        return result;
    }

    /**
     * 詳細判定 (監査・デバッグ用)。
     *
     * <p>未対応 type は {@link DenyReason#UNSUPPORTED_REFERENCE_TYPE} を返す。
     *
     * @param type      対象の reference_type
     * @param contentId 対象 contentId
     * @param userId    閲覧者 userId
     * @return 判定結果
     */
    public VisibilityDecision decide(ReferenceType type, Long contentId, Long userId) {
        ContentVisibilityResolver<?> resolver = resolverMap.get(type);
        if (resolver == null) {
            recordUnsupported(type);
            return VisibilityDecision.deny(
                type, contentId, DenyReason.UNSUPPORTED_REFERENCE_TYPE,
                "no resolver registered for referenceType=" + type);
        }
        return resolver.decide(contentId, userId);
    }

    /**
     * 例外スロー版判定 (Controller 入口など)。
     *
     * <p>{@link #decide} の結果を見て:
     * <ul>
     *   <li>{@code allowed=true} → 何もしない (return)
     *   <li>{@link DenyReason#NOT_FOUND} → {@code VISIBILITY_004} (404 相当) でスロー
     *   <li>その他 deny → {@code VISIBILITY_001} (403 相当) でスロー
     * </ul>
     *
     * <p>TODO (Phase A-6): {@code VisibilityErrorCode} 完成後、ここでスタブの
     * {@link StubVisibilityErrorCode} を本来の {@code VisibilityErrorCode.VISIBILITY_001
     * / VISIBILITY_004} に置き換えること。
     *
     * @param type      対象の reference_type
     * @param contentId 対象 contentId
     * @param userId    閲覧者 userId
     * @throws BusinessException 閲覧不可の場合
     */
    public void assertCanView(ReferenceType type, Long contentId, Long userId) {
        VisibilityDecision decision = decide(type, contentId, userId);
        if (decision.allowed()) {
            return;
        }
        if (decision.denyReason() == DenyReason.NOT_FOUND) {
            throw new BusinessException(StubVisibilityErrorCode.VISIBILITY_004);
        }
        throw new BusinessException(StubVisibilityErrorCode.VISIBILITY_001);
    }

    /**
     * 未対応 {@link ReferenceType} の検出を記録する。
     *
     * <p>tag cardinality 爆発防止のため、最終的には Micrometer メトリクス
     * (§9.4 {@code content_visibility.unsupported_reference_type}) に集約する。
     *
     * <p>TODO (Phase A-5b): {@code VisibilityMetrics} 完成後、{@code log.debug}
     * の後で {@code visibilityMetrics.recordUnsupported(type)} を呼ぶこと。
     * 現時点ではログ出力のみ。
     *
     * @param type 未対応として検出された reference_type
     */
    private void recordUnsupported(ReferenceType type) {
        // §15 D-12 に基づく fail-closed の WARN ログ。Phase 2 予約 type
        // (PERSONAL_TIMETABLE / FOLLOW_LIST) への到達はここで観測される。
        log.warn("Unsupported referenceType={}: no resolver registered (fail-closed)", type);
        if (log.isDebugEnabled()) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            // 0:getStackTrace 1:recordUnsupported 2:caller (canView 等) 3:外部呼び出し元
            if (stack.length > 3) {
                log.debug("Unsupported referenceType={} caller={}", type, stack[3]);
            }
        }
        // TODO (Phase A-5b): visibilityMetrics.recordUnsupported(type) を呼ぶ
    }

    /**
     * Phase A-1b 時点での暫定 ErrorCode スタブ。
     *
     * <p>TODO (Phase A-6): 本 enum を削除し、
     * {@code com.mannschaft.app.common.visibility.VisibilityErrorCode} に置き換えること。
     * メッセージは i18n properties 経由となる予定 (§7.4.1)。
     */
    private enum StubVisibilityErrorCode implements ErrorCode {
        /** 認可拒否 (権限不足) — 設計書 §7.4 で 403 にマップ予定. */
        VISIBILITY_001("VISIBILITY_001", "このコンテンツを閲覧する権限がありません"),
        /** 対象コンテンツ不在 — 設計書 §7.4 で 404 にマップ予定. */
        VISIBILITY_004("VISIBILITY_004", "指定のコンテンツが見つかりません");

        private final String code;
        private final String message;

        StubVisibilityErrorCode(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public Severity getSeverity() {
            return Severity.WARN;
        }
    }

}
