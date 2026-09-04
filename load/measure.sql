-- Run once BEFORE a flood and once AFTER lag returns to zero. Subtract.
SELECT n_tup_ins + n_tup_upd AS drivers_writes,
       (SELECT xact_commit FROM pg_stat_database WHERE datname = 'fleetmind') AS commits,
       now() AS at
FROM pg_stat_user_tables
WHERE relname = 'drivers';

-- No-loss check after the flood has fully drained. Expect count = drivers param (default 500).
SELECT count(*) AS drivers_at_final_seq
FROM drivers
WHERE id LIKE 'load-%' AND speed_kmph = 200;

-- Anything below the final sequence means a lost or out-of-order tail. Expect 0 rows.
SELECT id, speed_kmph
FROM drivers
WHERE id LIKE 'load-%' AND speed_kmph <> 200
ORDER BY speed_kmph
LIMIT 20;

-- Cleanup between runs.
DELETE FROM drivers WHERE id LIKE 'load-%';
