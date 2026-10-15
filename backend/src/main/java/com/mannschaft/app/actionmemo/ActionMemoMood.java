package com.mannschaft.app.actionmemo;

/**
 * F02.5 行動メモの気分（mood）プリセット。
 * 5段階。絵文字（😄🙂😐😩😞）はフロント側で付与する。
 * ユーザーが user_action_memo_settings.mood_enabled = false の場合、
 * 送信された mood 値は Service 層で silent に NULL へ置き換えられる。
 */
public enum ActionMemoMood {
    /** 絶好調 */
    GREAT,
    /** いい感じ */
    GOOD,
    /** 普通 */
    OK,
    /** 疲れ */
    TIRED,
    /** しんどい */
    BAD
}
