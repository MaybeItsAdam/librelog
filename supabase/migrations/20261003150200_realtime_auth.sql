-- Lets each signed-in device receive (never send) on its own private
-- broadcast channel, `user:<uid>`, which push_ops pings after every push.
--
-- Realtime creates realtime.messages the first time the service starts for a
-- project, which can be after this migration first runs. If it's missing,
-- this stops `supabase db push` with instructions; the earlier migrations
-- are already applied, so just re-run the push afterwards.

do $$
begin
  if to_regclass('realtime.messages') is null then
    raise exception 'realtime.messages does not exist yet'
      using hint = 'Open Realtime in the Supabase dashboard once (it initialises the schema), then run supabase db push again.';
  end if;
end $$;

create policy "dailylog: receive own sync pings" on realtime.messages
  for select to authenticated
  using (
    realtime.messages.extension = 'broadcast'
    and (select realtime.topic()) = 'user:' || (select auth.uid())::text
  );
