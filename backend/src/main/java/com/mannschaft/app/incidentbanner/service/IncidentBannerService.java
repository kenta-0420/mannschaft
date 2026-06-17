package com.mannschaft.app.incidentbanner.service;

import com.mannschaft.app.incidentbanner.entity.IncidentBannerEntity;
import com.mannschaft.app.incidentbanner.entity.IncidentBannerTranslationEntity;
import com.mannschaft.app.incidentbanner.repository.IncidentBannerRepository;
import com.mannschaft.app.incidentbanner.repository.IncidentBannerTranslationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 障害告知バナーサービス。
 *
 * <p>バナーの CRUD・公開制御・翻訳の upsert、およびユーザー向け公開バナー取得を担う。
 * 翻訳の自動生成・Claude 連携は二番隊の担当であり、本サービスでは実装しない。</p>
 *
 * <p>{@code @Transactional} は incidentbanner ドメイン内にのみ閉じる（アーキテクチャ原則5）。</p>
 */
@Service
@RequiredArgsConstructor
public class IncidentBannerService {

    private final IncidentBannerRepository bannerRepository;
    private final IncidentBannerTranslationRepository translationRepository;

    // =========================================================================
    // CRUD
    // =========================================================================

    /**
     * バナーを新規作成する。
     *
     * @param level            バナーレベル（"INFO", "WARNING", "ERROR"）
     * @param pagePattern      表示対象ページパターン
     * @param originalLanguage 基準言語
     * @param startsAt         表示開始日時（NULL で無制限）
     * @param endsAt           表示終了日時（NULL で無制限）
     * @param createdBy        作成者ユーザーID
     * @return 作成されたバナーエンティティ
     */
    @Transactional
    public IncidentBannerEntity create(String level, String pagePattern, String originalLanguage,
                                      LocalDateTime startsAt, LocalDateTime endsAt, Long createdBy) {
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level(level)
                .pagePattern(pagePattern)
                .originalLanguage(originalLanguage)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .createdBy(createdBy)
                .build();
        return bannerRepository.save(banner);
    }

    /**
     * バナーの表示設定を更新する。
     *
     * @param id               バナーID
     * @param level            バナーレベル
     * @param pagePattern      表示対象ページパターン
     * @param originalLanguage 基準言語
     * @param startsAt         表示開始日時（NULL で無制限）
     * @param endsAt           表示終了日時（NULL で無制限）
     * @return 更新後のバナーエンティティ
     * @throws NoSuchElementException バナーが存在しない場合
     */
    @Transactional
    public IncidentBannerEntity update(UUID id, String level, String pagePattern,
                                      String originalLanguage, LocalDateTime startsAt,
                                      LocalDateTime endsAt) {
        IncidentBannerEntity banner = findByIdOrThrow(id);
        banner.update(level, pagePattern, originalLanguage, startsAt, endsAt);
        return bannerRepository.save(banner);
    }

    /**
     * バナーを公開状態にする。
     *
     * @param id バナーID
     * @return 更新後のバナーエンティティ
     * @throws NoSuchElementException バナーが存在しない場合
     */
    @Transactional
    public IncidentBannerEntity publish(UUID id) {
        IncidentBannerEntity banner = findByIdOrThrow(id);
        banner.publish();
        return bannerRepository.save(banner);
    }

    /**
     * バナーを非公開状態にする。
     *
     * @param id バナーID
     * @return 更新後のバナーエンティティ
     * @throws NoSuchElementException バナーが存在しない場合
     */
    @Transactional
    public IncidentBannerEntity unpublish(UUID id) {
        IncidentBannerEntity banner = findByIdOrThrow(id);
        banner.unpublish();
        return bannerRepository.save(banner);
    }

    /**
     * バナーを論理削除する。
     *
     * @param id バナーID
     * @throws NoSuchElementException バナーが存在しない場合
     */
    @Transactional
    public void softDelete(UUID id) {
        IncidentBannerEntity banner = findByIdOrThrow(id);
        banner.softDelete();
        bannerRepository.save(banner);
    }

    /**
     * バナーをID で取得する。
     *
     * @param id バナーID
     * @return バナーエンティティ
     */
    @Transactional(readOnly = true)
    public Optional<IncidentBannerEntity> findById(UUID id) {
        return bannerRepository.findById(id);
    }

    /**
     * バナー一覧を取得する（管理用）。
     *
     * @param pageable ページング条件
     * @return バナー一覧（ページング）
     */
    @Transactional(readOnly = true)
    public Page<IncidentBannerEntity> list(Pageable pageable) {
        return bannerRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    // =========================================================================
    // 翻訳の upsert（二番隊との結合フック）
    // =========================================================================

    /**
     * バナーの翻訳メッセージを登録・更新（upsert）する。
     *
     * <p>同一 (bannerId, language) の翻訳が存在する場合は更新、
     * 存在しない場合は新規作成する。
     * 翻訳の自動生成（Claude 連携）は二番隊が担当し、
     * 本メソッドはそのフックポイントとして機能する。</p>
     *
     * @param bannerId バナーID
     * @param language 言語コード
     * @param message  翻訳メッセージ
     * @return upsert 後の翻訳エンティティ
     * @throws NoSuchElementException バナーが存在しない場合
     */
    @Transactional
    public IncidentBannerTranslationEntity upsertTranslation(UUID bannerId, String language,
                                                              String message) {
        // バナーの存在確認
        findByIdOrThrow(bannerId);

        return translationRepository.findByBannerIdAndLanguage(bannerId, language)
                .map(existing -> {
                    existing.updateMessage(message);
                    return translationRepository.save(existing);
                })
                .orElseGet(() -> {
                    IncidentBannerTranslationEntity translation =
                            IncidentBannerTranslationEntity.builder()
                                    .bannerId(bannerId)
                                    .language(language)
                                    .message(message)
                                    .build();
                    return translationRepository.save(translation);
                });
    }

    /**
     * 指定バナーの翻訳一覧を取得する。
     *
     * @param bannerId バナーID
     * @return 翻訳エンティティのリスト
     */
    @Transactional(readOnly = true)
    public List<IncidentBannerTranslationEntity> getTranslations(UUID bannerId) {
        return translationRepository.findByBannerId(bannerId);
    }

    // =========================================================================
    // ユーザー向け公開バナー取得
    // =========================================================================

    /**
     * 公開中・有効期間内のバナーを指定言語の翻訳で取得する。
     *
     * <p>翻訳解決の優先順位:
     * <ol>
     *   <li>指定言語の翻訳が存在する場合はそれを使用</li>
     *   <li>存在しない場合は {@code originalLanguage} の翻訳にフォールバック</li>
     *   <li>いずれも存在しない場合はメッセージ空文字</li>
     * </ol>
     * </p>
     *
     * @param language 表示言語コード（例: "ja", "en"）
     * @return 公開バナーのリスト（内部DTO）
     */
    @Transactional(readOnly = true)
    public List<ActiveBannerDto> getActivePublic(String language) {
        LocalDateTime now = LocalDateTime.now();
        List<IncidentBannerEntity> banners = bannerRepository.findActivePublicBanners(now);

        return banners.stream()
                .map(banner -> {
                    List<IncidentBannerTranslationEntity> translations =
                            translationRepository.findByBannerId(banner.getId());

                    // 指定言語の翻訳を探す
                    String message = translations.stream()
                            .filter(t -> language.equals(t.getLanguage()))
                            .map(IncidentBannerTranslationEntity::getMessage)
                            .findFirst()
                            // なければ originalLanguage にフォールバック
                            .orElseGet(() -> translations.stream()
                                    .filter(t -> banner.getOriginalLanguage().equals(t.getLanguage()))
                                    .map(IncidentBannerTranslationEntity::getMessage)
                                    .findFirst()
                                    .orElse(""));

                    return new ActiveBannerDto(
                            banner.getId(),
                            banner.getLevel(),
                            banner.getPagePattern(),
                            message,
                            banner.getStartsAt(),
                            banner.getEndsAt()
                    );
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 内部 DTO
    // =========================================================================

    /**
     * ユーザー向け公開バナーの内部 DTO。
     *
     * <p>Controller/外部 DTO は三番隊が担当する。</p>
     *
     * @param id          バナーID
     * @param level       バナーレベル
     * @param pagePattern 表示対象ページパターン
     * @param message     解決済みメッセージ（言語フォールバック済み）
     * @param startsAt    表示開始日時
     * @param endsAt      表示終了日時
     */
    public record ActiveBannerDto(
            UUID id,
            String level,
            String pagePattern,
            String message,
            LocalDateTime startsAt,
            LocalDateTime endsAt
    ) {}

    // =========================================================================
    // private ヘルパー
    // =========================================================================

    private IncidentBannerEntity findByIdOrThrow(UUID id) {
        return bannerRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "IncidentBanner が見つかりません: id=" + id));
    }
}
