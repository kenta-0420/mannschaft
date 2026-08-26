package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatusBucket;
import com.mannschaft.app.todo.TodoStatusLabelScope;
import com.mannschaft.app.todo.dto.CreateTodoStatusLabelRequest;
import com.mannschaft.app.todo.dto.TodoStatusLabelResponse;
import com.mannschaft.app.todo.dto.UpdateTodoStatusLabelRequest;
import com.mannschaft.app.todo.entity.TodoStatusLabelEntity;
import com.mannschaft.app.todo.repository.TodoStatusLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.todo.dto.TodoStatusLabelView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TODO カスタムステータスラベルサービス（F02.3.1 Phase 1a）。
 *
 * <p>SYSTEM 既定ラベル + 個人/チーム/組織スコープのラベルを管理する。
 * SYSTEM ラベルは不変、個人スコープは本人のみ、チーム・組織スコープは ADMIN のみ
 * 編集可能。1スコープあたり最大 20 件。</p>
 *
 * <p><b>認可の所在</b>: 参照・作成・更新・削除の全経路が {@code validateScopeAccess} を通る。
 * チーム・組織スコープの<b>参照はメンバーに限定</b>し、<b>CRUD は ADMIN に限定</b>する。
 * 更新・削除ではさらに、path から渡されたスコープと<b>ラベル本体（entity）のスコープが一致すること</b>を
 * 先に照合し、不一致は 404（{@link TodoErrorCode#STATUS_LABEL_NOT_FOUND}）で存在を秘匿する。
 * 認可判定は必ず entity 由来のスコープで行い、リクエスト値をそのまま信頼しない（BOLA/IDOR 対策）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoStatusLabelService {

    /** スコープあたりのラベル数上限（SYSTEM は除外）。 */
    public static final int MAX_LABELS_PER_SCOPE = 20;

    private final TodoStatusLabelRepository labelRepository;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * 自己プロキシ参照（issue #2544）。{@code @Cacheable} は Spring AOP プロキシ経由でのみ作用するため、
     * {@link #findSystemDefaultByBucket} から {@link #getSystemDefaultsByBucket} を {@code this.} で
     * 呼ぶとプロキシをバイパスし、{@code systemDefaultLabels} キャッシュが一度も発火しない
     * （唯一の呼び出し元が同一クラス内なので、実質「死んだ注釈」になっていた）。
     * 循環参照を避けるため {@code @Lazy} を付けたフィールド注入とする。
     */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private TodoStatusLabelService self;

    /**
     * SYSTEM 既定ラベル + 当該スコープのアクティブラベル一覧を sort_order 順で取得する。
     *
     * @param scopeType スコープ種別（SYSTEM 単独取得は不可。SYSTEM 既定は常に先頭に含まれる）
     * @param scopeId   スコープ ID（PERSONAL/TEAM/ORGANIZATION のとき必須、SYSTEM のとき NULL）
     * @param actorId   操作ユーザー ID（権限判定用）
     * @return ラベル一覧
     */
    public List<TodoStatusLabelResponse> list(TodoStatusLabelScope scopeType, Long scopeId, Long actorId) {
        validateScopeAccess(scopeType, scopeId, actorId, false);

        List<TodoStatusLabelEntity> result = new ArrayList<>(labelRepository.findAllSystemDefaults());
        if (scopeType != TodoStatusLabelScope.SYSTEM) {
            result.addAll(labelRepository.findActiveByScope(scopeType, scopeId));
        }
        return result.stream().map(this::toResponse).toList();
    }

    /**
     * カスタムラベルを新規作成する。
     *
     * @param scopeType スコープ種別（SYSTEM 不可）
     * @param scopeId   スコープ ID
     * @param request   作成リクエスト
     * @param actorId   操作ユーザー ID
     * @return 作成されたラベル
     */
    @Transactional
    public TodoStatusLabelResponse create(TodoStatusLabelScope scopeType, Long scopeId,
                                          CreateTodoStatusLabelRequest request, Long actorId) {
        if (scopeType == TodoStatusLabelScope.SYSTEM) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }
        validateScopeAccess(scopeType, scopeId, actorId, true);

        // 同名重複チェック
        if (labelRepository.existsActiveByScopeAndName(scopeType, scopeId, request.getName())) {
            throw new BusinessException(TodoErrorCode.LABEL_NAME_DUPLICATED);
        }

        // 上限チェック（SYSTEM は除外）
        long count = labelRepository.countActiveByScope(scopeType, scopeId);
        if (count >= MAX_LABELS_PER_SCOPE) {
            throw new BusinessException(TodoErrorCode.LABEL_LIMIT_EXCEEDED);
        }

        TodoStatusBucket bucket = parseBucket(request.getBucket());

        TodoStatusLabelEntity entity = TodoStatusLabelEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .bucket(bucket)
                .color(request.getColor())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : 0)
                .isSystemDefault(false)
                .createdBy(actorId)
                .build();

        entity = labelRepository.save(entity);

        log.info("TODOステータスラベル作成: id={}, scope={}:{}, name={}",
                entity.getId(), scopeType, scopeId, entity.getName());
        recordAuditLog(AuditEventType.TODO_STATUS_LABEL_CREATED.name(), scopeType, scopeId, actorId, entity);

        return toResponse(entity);
    }

    /**
     * カスタムラベルを更新する。
     *
     * <p>IDOR 対策: path から渡された expectedScope / expectedScopeId と、ラベル本体の
     * scope_type / scope_id が一致しない場合は 404 LABEL_NOT_FOUND を返す（403 ではなく
     * 404 — リソース存在情報の漏洩を避けるため）。</p>
     *
     * @param labelId          ラベル ID
     * @param expectedScope    path から渡されるスコープ種別（PERSONAL/TEAM/ORGANIZATION）
     * @param expectedScopeId  path から渡されるスコープ ID
     * @param request          更新リクエスト
     * @param actorId          操作ユーザー ID
     * @return 更新後のラベル
     */
    @Transactional
    public TodoStatusLabelResponse update(Long labelId, TodoStatusLabelScope expectedScope,
                                          Long expectedScopeId,
                                          UpdateTodoStatusLabelRequest request, Long actorId) {
        TodoStatusLabelEntity entity = labelRepository.findActiveById(labelId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));

        // IDOR 対策: path scope と entity scope の整合チェック（不一致は 404 で漏らさない）
        if (entity.getScopeType() != expectedScope
                || !java.util.Objects.equals(entity.getScopeId(), expectedScopeId)) {
            throw new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND);
        }

        if (entity.isSystemDefault()) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        validateScopeAccess(entity.getScopeType(), entity.getScopeId(), actorId, true);

        // 名前変更時は重複チェック
        if (request.getName() != null && !request.getName().equals(entity.getName())) {
            if (labelRepository.existsActiveByScopeAndNameExcludingId(
                    entity.getScopeType(), entity.getScopeId(), request.getName(), labelId)) {
                throw new BusinessException(TodoErrorCode.LABEL_NAME_DUPLICATED);
            }
            entity.rename(request.getName());
        }

        if (request.getColor() != null) {
            entity.recolor(request.getColor());
        }

        if (request.getSortOrder() != null) {
            entity.reorder(request.getSortOrder());
        }

        if (request.getBucket() != null) {
            entity.changeBucket(parseBucket(request.getBucket()));
        }

        entity = labelRepository.save(entity);

        log.info("TODOステータスラベル更新: id={}, scope={}:{}",
                entity.getId(), entity.getScopeType(), entity.getScopeId());
        recordAuditLog(AuditEventType.TODO_STATUS_LABEL_UPDATED.name(), entity.getScopeType(), entity.getScopeId(), actorId, entity);

        return toResponse(entity);
    }

    /**
     * カスタムラベルを論理削除する。SYSTEM 既定ラベルは削除不可、使用中ラベルも削除不可。
     *
     * <p>IDOR 対策: path から渡された expectedScope / expectedScopeId と、ラベル本体の
     * scope_type / scope_id が一致しない場合は 404 LABEL_NOT_FOUND を返す。</p>
     *
     * @param labelId          ラベル ID
     * @param expectedScope    path から渡されるスコープ種別
     * @param expectedScopeId  path から渡されるスコープ ID
     * @param actorId          操作ユーザー ID
     */
    @Transactional
    public void delete(Long labelId, TodoStatusLabelScope expectedScope,
                       Long expectedScopeId, Long actorId) {
        TodoStatusLabelEntity entity = labelRepository.findActiveById(labelId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));

        // IDOR 対策: path scope と entity scope の整合チェック
        if (entity.getScopeType() != expectedScope
                || !java.util.Objects.equals(entity.getScopeId(), expectedScopeId)) {
            throw new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND);
        }

        if (entity.isSystemDefault()) {
            throw new BusinessException(TodoErrorCode.SYSTEM_LABEL_IMMUTABLE);
        }

        validateScopeAccess(entity.getScopeType(), entity.getScopeId(), actorId, true);

        long inUse = labelRepository.countTodosUsing(labelId);
        if (inUse > 0) {
            throw new BusinessException(TodoErrorCode.LABEL_IN_USE);
        }

        entity.softDelete();
        labelRepository.save(entity);

        log.info("TODOステータスラベル削除: id={}, scope={}:{}",
                entity.getId(), entity.getScopeType(), entity.getScopeId());
        recordAuditLog(AuditEventType.TODO_STATUS_LABEL_DELETED.name(), entity.getScopeType(), entity.getScopeId(), actorId, entity);
    }

    /**
     * ラベルが TODO のスコープで使用可能かを検証する。
     * SYSTEM ラベルは全スコープで使用可。
     *
     * @param label     対象ラベル
     * @param scopeType TODO のスコープ種別
     * @param scopeId   TODO のスコープ ID
     * @throws BusinessException スコープ不一致のとき LABEL_SCOPE_MISMATCH
     */
    public void validateLabelForScope(TodoStatusLabelEntity label, TodoScopeType scopeType, Long scopeId) {
        if (label.getScopeType() == TodoStatusLabelScope.SYSTEM) {
            return;
        }
        TodoStatusLabelScope expectedScope = mapTodoScope(scopeType);
        if (label.getScopeType() != expectedScope || !java.util.Objects.equals(label.getScopeId(), scopeId)) {
            throw new BusinessException(TodoErrorCode.LABEL_SCOPE_MISMATCH);
        }
    }

    /**
     * ID でアクティブラベルを取得する（TodoService から呼び出される公開メソッド）。
     */
    public TodoStatusLabelEntity findActiveById(Long id) {
        return labelRepository.findActiveById(id)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.STATUS_LABEL_NOT_FOUND));
    }

    /**
     * 複数 ID のラベルを一括取得する（一覧 API の N+1 対策で TodoService から使用）。
     * 削除済みラベルは含まれない。
     */
    public List<TodoStatusLabelEntity> findActiveByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return labelRepository.findAllById(ids).stream()
                .filter(l -> l.getDeletedAt() == null)
                .toList();
    }

    /**
     * SYSTEM 既定ラベルを bucket名 → entity の Map で取得する（F02.3.1 後続 B-6）。
     *
     * <p>{@link TodoStatusBucket} ごとに1件ずつ存在することを想定。SYSTEM 既定ラベルは
     * V19.003 マイグレーションで投入され、論理削除も改名も発生しないため
     * {@link Cacheable} でキャッシュする。バケット → ラベル の即時参照に使用。</p>
     *
     * <p><strong>キーが {@link TodoStatusBucket} ではなく {@code String}（= {@code bucket.name()}）
     * である理由:</strong> Redis(Valkey) の JSON シリアライザ（{@code GenericJackson2JsonRedisSerializer}）
     * は Map を JSON オブジェクト化するが、JSON のキーは常に文字列であり、デシリアライズ時に
     * 「キーが enum 型である」という情報が失われる。{@code EnumMap<TodoStatusBucket, ...>} を
     * キャッシュするとキャッシュ HIT 時にキーが {@code String} 化した Map が返り、
     * {@code map.get(bucket)}（enum キー）が常に null を返して既定ラベル参照が静かに壊れる。
     * キーを最初から {@code String} にすることで JSON ラウンドトリップで形が崩れない。</p>
     *
     * <p><b>戻り値を呼び出し側で変異させないこと（issue #2544）。</b>
     * 復元可能性のため不変コレクションをやめて可変の実装を返しているが、
     * これは「変更してよい」という意味ではない。test プロファイルの
     * {@code ConcurrentMapCacheManager} はキャッシュ済みの<b>同一インスタンス</b>を返すため、
     * 呼び出し側が {@code add}/{@code remove}/{@code put} するとキャッシュ本体が汚染され、
     * 以降の全呼び出し元が汚染後の値を受け取る（本番の Valkey は毎回デシリアライズするので
     * 症状が出ず、<b>テストと本番で挙動が食い違う</b>厄介な形になる）。
     * 加工が要る場合は必ずコピーしてから行うこと。</p>
     *
     * @return bucket名（{@link TodoStatusBucket#name()}）をキーとした SYSTEM 既定ラベルのマップ
     *         （空はあり得ないが、欠落時は空の Map を返す）
     */
    @Cacheable("systemDefaultLabels")
    public Map<String, TodoStatusLabelView> getSystemDefaultsByBucket() {
        Map<String, TodoStatusLabelView> result = new java.util.LinkedHashMap<>();
        for (TodoStatusLabelEntity entity : labelRepository.findAllSystemDefaults()) {
            // 同一 bucket が複数あった場合は sort_order が小さい方を優先（findAllSystemDefaults が ASC 順）
            // issue #2544 D 群: JPA エンティティは setter を持たず往復で全 null 化するため View へ射影する。
            result.putIfAbsent(entity.getBucket().name(),
                    new TodoStatusLabelView(
                            entity.getId(),
                            entity.getName(),
                            entity.getBucket().name(),
                            entity.getColor()));
        }
        // issue #2544 B 群: Map.copyOf(...) は java.util.ImmutableCollections$MapN を返し、
        // RedisConfig が埋め込む具象型 ID から復元できない（既定コンストラクタが無い）。
        // 可変の LinkedHashMap をそのまま返す（挿入順＝sort_order 昇順も保たれる）。
        return result;
    }

    /**
     * 指定 bucket の SYSTEM 既定ラベルを取得する（F02.3.1 後続 B-6）。
     *
     * @param bucket 取得したいバケット
     * @return SYSTEM 既定ラベルの View（マイグレーション欠落時は empty）
     */
    public Optional<TodoStatusLabelView> findSystemDefaultByBucket(TodoStatusBucket bucket) {
        if (bucket == null) {
            return Optional.empty();
        }
        // issue #2544: 自己プロキシ経由で呼ぶ（this. だと @Cacheable が発火しない）。
        return Optional.ofNullable(self.getSystemDefaultsByBucket().get(bucket.name()));
    }

    // ─────────────────────────────────────────────
    // 内部ヘルパー
    // ─────────────────────────────────────────────

    /**
     * スコープへのアクセス権を検証する。
     *
     * <p>保証する内容（F02.3.1 設計書 §2 の権限マトリクス）:</p>
     * <ul>
     *   <li><b>個人スコープ</b>: 参照・CRUD とも本人のみ（{@code actorId == scopeId}）。違反時は 403。</li>
     *   <li><b>チーム・組織スコープの参照</b>: 当該スコープの<b>メンバーに限定</b>する
     *       （{@link AccessControlService#checkMembership}）。非メンバーは 403。</li>
     *   <li><b>チーム・組織スコープの CRUD</b>: <b>ADMIN のみ</b>（DEPUTY_ADMIN は不可）。
     *       設計書を正として {@link AccessControlService#isAdmin} で厳格判定する。違反時は 403。</li>
     * </ul>
     *
     * <p>参照とCRUDのいずれの経路でも認可判定を必ず通す（無条件に素通しする分岐を持たない）。
     * ラベルは所属スコープの運用語彙であり、スコープ外の利用者には参照させない方針である。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープ ID
     * @param actorId     操作ユーザー ID
     * @param adminOnly   true の場合は CRUD 権限（個人=本人 / チーム・組織=ADMIN のみ）、
     *                    false の場合は参照権限（個人=本人 / チーム・組織=メンバー）
     */
    private void validateScopeAccess(TodoStatusLabelScope scopeType, Long scopeId, Long actorId, boolean adminOnly) {
        if (actorId == null) {
            throw new BusinessException(CommonErrorCode.COMMON_000);
        }
        switch (scopeType) {
            case SYSTEM:
                // SYSTEM は閲覧自由、書き込みはこのメソッドに到達する前に弾く
                return;
            case PERSONAL:
                if (!actorId.equals(scopeId)) {
                    throw new BusinessException(CommonErrorCode.COMMON_002);
                }
                return;
            case TEAM:
                if (adminOnly) {
                    requireAdminStrict(actorId, scopeId, "TEAM");
                } else {
                    // 参照は当該チームのメンバーに限定する（非メンバーは 403）。
                    accessControlService.checkMembership(actorId, scopeId, "TEAM");
                }
                return;
            case ORGANIZATION:
                if (adminOnly) {
                    requireAdminStrict(actorId, scopeId, "ORGANIZATION");
                } else {
                    // 参照は当該組織のメンバーに限定する（非メンバーは 403）。
                    accessControlService.checkMembership(actorId, scopeId, "ORGANIZATION");
                }
                return;
            default:
                throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * ADMIN のみを許容する厳格判定（DEPUTY_ADMIN は弾く）。違反時は 403。
     * 設計書 §2 の権限マトリクスを正とする。
     */
    private void requireAdminStrict(Long actorId, Long scopeId, String scopeType) {
        if (!accessControlService.isAdmin(actorId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private TodoStatusBucket parseBucket(String value) {
        try {
            return TodoStatusBucket.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(TodoErrorCode.STATUS_LABEL_BUCKET_MISMATCH);
        }
    }

    private TodoStatusLabelScope mapTodoScope(TodoScopeType type) {
        switch (type) {
            case PERSONAL:
                return TodoStatusLabelScope.PERSONAL;
            case TEAM:
                return TodoStatusLabelScope.TEAM;
            case ORGANIZATION:
                return TodoStatusLabelScope.ORGANIZATION;
            default:
                throw new BusinessException(TodoErrorCode.LABEL_SCOPE_MISMATCH);
        }
    }

    private TodoStatusLabelResponse toResponse(TodoStatusLabelEntity entity) {
        return new TodoStatusLabelResponse(
                entity.getId(),
                entity.getScopeType().name(),
                entity.getScopeId(),
                entity.getName(),
                entity.getBucket().name(),
                entity.getColor(),
                entity.getSortOrder(),
                entity.isSystemDefault(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private void recordAuditLog(String eventType, TodoStatusLabelScope scopeType, Long scopeId,
                                Long actorId, TodoStatusLabelEntity entity) {
        Long teamId = scopeType == TodoStatusLabelScope.TEAM ? scopeId : null;
        Long orgId = scopeType == TodoStatusLabelScope.ORGANIZATION ? scopeId : null;
        String metadata = String.format(
                "{\"labelId\":%d,\"scopeType\":\"%s\",\"scopeId\":%s,\"name\":\"%s\",\"bucket\":\"%s\"}",
                entity.getId(), entity.getScopeType().name(),
                entity.getScopeId() == null ? "null" : entity.getScopeId().toString(),
                escapeJson(entity.getName()), entity.getBucket().name()
        );
        auditLogService.record(eventType, actorId, null, teamId, orgId, null, null, null, metadata);
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
