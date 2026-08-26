package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.SignageLayout;
import com.mannschaft.app.signage.SignageTransitionEffect;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * デジタルサイネージ 画面管理サービス。
 * 画面の作成・取得・更新・論理削除を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageScreenService {

    /** スコープ内の画面数上限 */
    private static final int MAX_SCREENS_PER_SCOPE = 10;

    private final SignageScreenRepository screenRepository;
    private final AccessControlService accessControlService;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * 画面作成リクエスト DTO。
     */
    public record CreateSignageScreenRequest(
            String scopeType,
            Long scopeId,
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect
    ) {}

    /**
     * 画面更新リクエスト DTO。
     */
    public record UpdateSignageScreenRequest(
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect,
            Boolean isActive
    ) {}

    /**
     * 画面レスポンス DTO。
     */
    public record SignageScreenResponse(
            Long id,
            String scopeType,
            Long scopeId,
            String name,
            String description,
            SignageLayout layout,
            Integer defaultSlideDuration,
            SignageTransitionEffect transitionEffect,
            Boolean isActive,
            java.time.LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 画面を作成する。スコープ内10画面制限あり。
     *
     * @param createdBy 作成者ユーザーID
     * @param req       作成リクエスト
     * @return 作成した画面レスポンス
     */
    @Transactional
    public SignageScreenResponse createScreen(Long createdBy, CreateSignageScreenRequest req) {
        // 認可: 対象スコープの ADMIN/DEPUTY_ADMIN のみ画面作成可能
        accessControlService.checkAdminOrAbove(createdBy, req.scopeId(), req.scopeType());

        // スコープ内10画面制限チェック
        List<SignageScreenEntity> existing = screenRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(req.scopeType(), req.scopeId());
        if (existing.size() >= MAX_SCREENS_PER_SCOPE) {
            throw new BusinessException(SignageErrorCode.SIGNAGE_001);
        }

        SignageScreenEntity entity = SignageScreenEntity.builder()
                .scopeType(req.scopeType())
                .scopeId(req.scopeId())
                .name(req.name())
                .layout(req.layout() != null ? req.layout() : SignageLayout.LANDSCAPE)
                .defaultSlideDuration(req.defaultSlideDuration() != null ? req.defaultSlideDuration() : 10)
                .transitionEffect(req.transitionEffect() != null ? req.transitionEffect() : SignageTransitionEffect.FADE)
                .createdBy(createdBy)
                .build();

        SignageScreenEntity saved = screenRepository.save(entity);
        log.info("サイネージ画面作成: id={}, scope={}/{}, name={}", saved.getId(), req.scopeType(), req.scopeId(), req.name());
        return toResponse(saved);
    }

    /**
     * 指定IDの画面を取得する。
     *
     * <p><b>認可は呼び出し元の責務</b>。サイネージ端末向けの公開表示経路
     * （{@code SignageDisplayController#getDisplayConfig}）はトークン認証済みで
     * ユーザーコンテキストを持たないため、本メソッドには認可を敷かない。
     * 認証ユーザー向けの管理画面入口は {@link #getScreenForActor} を使うこと。</p>
     *
     * @param id 画面ID
     * @return 画面レスポンス
     */
    public SignageScreenResponse getScreen(Long id) {
        SignageScreenEntity entity = findScreenOrThrow(id);
        return toResponse(entity);
    }

    /**
     * 認証ユーザー向けに指定IDの画面を取得する（メンバーシップ必須）。
     *
     * <p>認可根治戦役 Wave7: 認証ユーザー向けの管理画面入口として
     * {@link AccessControlService#checkMembership} でスコープの会員に限定する。
     * 兄弟の {@link #createScreen}/{@link #updateScreen} は ADMIN 限定だが、参照は会員であれば
     * 可とする（読取＝会員・変更＝ADMINの通例）。</p>
     */
    public SignageScreenResponse getScreenForActor(Long id, Long actor) {
        SignageScreenEntity entity = findScreenOrThrow(id);
        accessControlService.checkMembership(actor, entity.getScopeId(), entity.getScopeType());
        return toResponse(entity);
    }

    /**
     * スコープに紐づくアクティブな画面一覧を取得する。
     *
     * <p><b>認可は呼び出し元の責務</b>。{@link #getScreen} と同じ理由でサイネージ端末向け
     * 経路からも共有されるため認可を敷かない。認証ユーザー向けは
     * {@link #listScreensForActor} を使うこと。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 画面レスポンス一覧
     */
    public List<SignageScreenResponse> listScreens(String scopeType, Long scopeId) {
        return screenRepository
                .findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(scopeType, scopeId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 認証ユーザー向けにスコープの画面一覧を取得する（メンバーシップ必須）。
     *
     * <p>認可根治戦役 Wave7: {@link #getScreenForActor} と同一の理由。</p>
     */
    public List<SignageScreenResponse> listScreensForActor(String scopeType, Long scopeId, Long actor) {
        accessControlService.checkMembership(actor, scopeId, scopeType);
        return listScreens(scopeType, scopeId);
    }

    /**
     * 画面を更新する。
     *
     * @param id     画面ID
     * @param actor  操作者ユーザーID
     * @param req    更新リクエスト
     * @return 更新後画面レスポンス
     */
    @Transactional
    public SignageScreenResponse updateScreen(Long id, Long actor, UpdateSignageScreenRequest req) {
        SignageScreenEntity entity = findScreenOrThrow(id);

        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみ更新可能
        accessControlService.checkAdminOrAbove(actor, entity.getScopeId(), entity.getScopeType());

        // managed entity を直接ミューテートして save することで id=null INSERT を防ぐ。
        // toBuilder().build() では @Builder が BaseEntity の id を引き継がず id=null になるため使用禁止。
        entity.applyUpdate(
                req.name(),
                req.layout(),
                req.defaultSlideDuration(),
                req.transitionEffect(),
                req.isActive()
        );

        SignageScreenEntity saved = screenRepository.save(entity);
        log.info("サイネージ画面更新: id={}", id);
        return toResponse(saved);
    }

    /**
     * 画面を論理削除する。
     *
     * @param id    画面ID
     * @param actor 操作者ユーザーID
     */
    @Transactional
    public void deleteScreen(Long id, Long actor) {
        SignageScreenEntity entity = findScreenOrThrow(id);

        // 認可: 当該画面スコープの ADMIN/DEPUTY_ADMIN のみ削除可能
        accessControlService.checkAdminOrAbove(actor, entity.getScopeId(), entity.getScopeType());

        entity.softDelete();
        screenRepository.save(entity);
        log.info("サイネージ画面論理削除: id={}", id);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * IDで画面を取得する。見つからない場合は SIGNAGE_001 例外をスローする。
     */
    public SignageScreenEntity findScreenOrThrow(Long id) {
        return screenRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));
    }

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private SignageScreenResponse toResponse(SignageScreenEntity e) {
        return new SignageScreenResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                null, // description フィールドは Entity に存在しないためnull
                e.getLayout(),
                e.getDefaultSlideDuration(),
                e.getTransitionEffect(),
                e.getIsActive(),
                e.getCreatedAt()
        );
    }
}
