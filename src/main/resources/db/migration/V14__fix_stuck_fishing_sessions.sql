-- Фикс застрявших рыболовных сессий: баг навигации через рюкзак обнулял
-- fishing_spot пока рыбалка шла. Итог: fishing_finish_at есть, spot = NULL,
-- игрок не может забрать улов (экран завис, бот молчал на NPE).
--
-- Решение:
--   1. Ставим spot = SHORE — это наименьшее что игрок мог поймать (честно).
--   2. Ставим current_screen = FISHING_RESULT — игрок увидит экран улова
--      при следующем нажатии любой кнопки.

UPDATE player_fishing pf
    JOIN player_state ps ON ps.player_id = pf.player_id
SET
    pf.fishing_spot    = 'SHORE',
    ps.current_screen  = 'FISHING_RESULT'
WHERE
    pf.fishing_finish_at IS NOT NULL
    AND pf.fishing_finish_at <= NOW()
    AND pf.fishing_spot IS NULL;
