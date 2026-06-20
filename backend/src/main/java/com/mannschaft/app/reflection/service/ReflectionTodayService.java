package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.ReflectionTodayResponse;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 今日の振り返りビューのサービス（F06.5・§4.3 / §7 #12）。
 *
 * <p><b>第二陣スケルトン</b>: シグネチャ・依存注入のみ確定。本体ロジック（
 * {@code PersonalTimetableDashboardService.getTimetableToday()} の実呼び出しでコマ列挙→
 * 当日エントリ／マスク状態を付与→自由テーマ item と混在で縦並び・ユーザー TZ の今日算出＝AC-17）は
 * 次陣（試練 red→出陣 green）。</p>
 *
 * <p>注: コマ列挙は {@code PersonalTimetableDashboardService} への依存注入で実呼び出しする設計（§4.3）。
 * スケルトン段では循環依存・Bean 解決の安定性を優先して当該依存は未注入とし、次陣で追加する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionTodayService {

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;

    /**
     * 今日の振り返りビュー（§7 #12・AC-17/AC-18）。
     *
     * @param userId 認証ユーザーID
     * @param date   対象日（null ならサービスがユーザー TZ の今日を採用・§4.3）
     */
    public ReflectionTodayResponse getToday(Long userId, LocalDate date) {
        throw new UnsupportedOperationException("F06.5 未実装: §7 #12 今日の振り返りビュー");
    }
}
