-- Runs dailylog.maintenance() every night at 03:17 UTC (§12.5).
-- cron.schedule replaces an existing job with the same name.

create extension if not exists pg_cron;

select cron.schedule(
  'dailylog-maintenance',
  '17 3 * * *',
  $$select dailylog.maintenance()$$
);
