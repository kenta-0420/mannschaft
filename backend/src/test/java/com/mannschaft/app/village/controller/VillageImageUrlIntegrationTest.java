package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * F17 #2355 — 村画像 URL 解決の統合テスト（実 DB 往復）。
 *
 * <p><b>再現しようとしている実機症状</b>: 村紋を登録すると PUT のレスポンスには映るが、
 * 画面をリロードすると消える。原因は {@code VillageService#toResponse} が
 * {@code monshoR2Key} を一切載せていなかったこと（DB には保存されているのに読み出せない）。
 * モック UT では「toResponse に載っていない」ことしか見えないため、
 * ここでは <b>DB へ実際に永続化 → 別経路で取得</b> の往復で症状そのものを固定する。</p>
 *
 * <p>{@link R2StorageService} は外部境界（署名計算）であり環境差で結果が揺れるため
 * mock 化し、署名 URL の生成有無ではなく「解決結果がレスポンスへ載るか」に検証を絞る。</p>
 */
@DisplayName("村画像URL解決 統合テスト（#2355）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageImageUrlIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageService villageService;

    @Autowired
    private VillageRepository villageRepository;

    /** 署名計算は外部境界。環境差を排除するため決定論的な mock に差し替える。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long REGULAR_USER_ID = 9_720_101L;

    private static final String ICON_KEY = "village/icon/it-icon.png";
    private static final String COVER_KEY = "village/cover/it-cover.png";
    private static final String MONSHO_KEY = "village/monsho/it-monsho.png";

    @BeforeEach
    void setUp() {
        lenient().when(accessControlService.isSystemAdmin(anyLong())).thenReturn(false);
        // 生キーを受け取り、絶対 URL を返す（実 R2 と同じ形）
        lenient().when(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .thenAnswer(inv -> "https://r2.example.com/" + inv.getArgument(0) + "?sig=it");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-1: DB に村紋が永続化された村を取得すると monshoUrl が返る（リロードで消える症状の固定）")
    void get_persistedMonshoSurvivesRoundTrip() {
        VillageEntity saved = villageRepository.save(villageWithImages());

        VillageResponse res = villageService.get(saved.getId(), REGULAR_USER_ID);

        assertThat(res.monshoUrl())
                .as("村紋は DB に保存されているのに GET のレスポンスへ載っておらず、リロードで消えていた")
                .isNotNull()
                .startsWith("http");
    }

    @Test
    @DisplayName("AC-12: icon/cover/monsho の3点すべてが絶対URLとして往復する")
    void get_allThreeImagesSurviveRoundTrip() {
        VillageEntity saved = villageRepository.save(villageWithImages());

        VillageResponse res = villageService.get(saved.getId(), REGULAR_USER_ID);

        assertThat(res.iconUrl()).as("iconUrl").isNotNull().startsWith("http");
        assertThat(res.coverUrl()).as("coverUrl").isNotNull().startsWith("http");
        assertThat(res.monshoUrl()).as("monshoUrl").isNotNull().startsWith("http");
    }

    @Test
    @DisplayName("AC-3: 画像未設定の村でも例外にならず、3点とも null で返る")
    void get_villageWithoutImagesReturnsNullUrls() {
        VillageEntity saved = villageRepository.save(baseVillage());

        assertThatCode(() -> {
            VillageResponse res = villageService.get(saved.getId(), REGULAR_USER_ID);
            assertThat(res.iconUrl()).isNull();
            assertThat(res.coverUrl()).isNull();
            assertThat(res.monshoUrl()).isNull();
        }).doesNotThrowAnyException();
    }

    // ─────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────

    private VillageEntity baseVillage() {
        // 日時は文字列リテラルでなく LocalDateTime でバインドする（JST/UTC 9h ズレ事故の回避）
        LocalDateTime now = LocalDateTime.now();
        return VillageEntity.builder()
                .slug("image-url-it-" + System.nanoTime() % 100_000)
                .name("画像URL検証村")
                .description("#2355 画像URL解決の統合テスト用")
                .type(VillageType.OFFICIAL)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(REGULAR_USER_ID)
                .createdAt(now)
                .updatedAt(now)
                .version(0L)
                .build();
    }

    private VillageEntity villageWithImages() {
        VillageEntity e = baseVillage();
        e.setIconR2Key(ICON_KEY);
        e.setCoverR2Key(COVER_KEY);
        e.setMonshoR2Key(MONSHO_KEY);
        return e;
    }
}
