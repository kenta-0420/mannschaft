package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.CreateReflectionThemeRequest;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionThemeRequest;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 振り返りテーマのサービス（F06.5・§7 #1〜#5）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（テーマ数上限 100 の検証・
 * exam_date 設定時の PRE_EXAM 再生成・CASCADE 論理削除＋PENDING CANCEL 等）は次陣（試練 red→出陣 green）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionThemeService {

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSpacedReminderService reflectionSpacedReminderService;

    /** 自分のテーマ一覧（§7 #1）。 */
    public List<ReflectionThemeResponse> listMyThemes(Long userId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #1 テーマ一覧");
    }

    /** テーマ作成（§7 #2・テーマ数上限 100 検証＝AC-3／§2.5.1(b)）。 */
    public ReflectionThemeResponse createTheme(Long userId, CreateReflectionThemeRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #2 テーマ作成");
    }

    /** テーマ詳細（§7 #3・本人所有検証＝AC-2）。 */
    public ReflectionThemeResponse getTheme(Long userId, UUID themeId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #3 テーマ詳細");
    }

    /** テーマ更新（§7 #4・exam_date 設定で PRE_EXAM 再生成＝AC-12／§5.5）。 */
    public ReflectionThemeResponse updateTheme(Long userId, UUID themeId, UpdateReflectionThemeRequest request) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #4 テーマ更新");
    }

    /** テーマ論理削除（§7 #5・配下 entry も CASCADE 論理削除＋PENDING リマインダ CANCEL）。 */
    public void deleteTheme(Long userId, UUID themeId) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #5 テーマ論理削除");
    }
}
