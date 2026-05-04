-- F09.8 件3': ピン止め時付箋メモ機能 — 付箋専用色（カラーラベルとは独立）
-- null = カラーラベル (color_label) と同色を意味する
-- 値あり = ピン時に明示的に選択された付箋色（YELLOW/BLUE/GREEN/RED/PURPLE/GRAY 等）
ALTER TABLE corkboard_cards
    ADD COLUMN note_color VARCHAR(20) NULL AFTER user_note;
