-- comment_option カラムに残存する旧値 'ALLOWED' を 'OPTIONAL' に修正する。
-- CommentOption enum が HIDDEN/OPTIONAL/REQUIRED の3値で定義された際、
-- 旧値 ALLOWED（コメント入力を許可する＝任意）は OPTIONAL に相当するため移行する。
UPDATE schedules
SET comment_option = 'OPTIONAL'
WHERE comment_option = 'ALLOWED';
