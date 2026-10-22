-- Behaviour tests for store purchases (record_purchase, sync_status). Run by
-- run.sh after the migrations. Any failed assertion aborts with an error.

\set ON_ERROR_STOP on
\o /dev/null

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000000c1', 'carol@example.com'),
  ('00000000-0000-0000-0000-0000000000d1', 'dave@example.com');

create function pg_temp.expect_error(sql text, code text) returns void language plpgsql as $$
begin
  execute sql;
  raise exception 'expected % from: %', code, sql;
exception when others then
  if sqlstate <> code then
    raise exception 'expected %, got % (%) from: %', code, sqlstate, sqlerrm, sql;
  end if;
end $$;
grant execute on function pg_temp.expect_error(text, text) to authenticated, service_role;

create function pg_temp.as_user(uid text) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, false)
$$;
grant execute on function pg_temp.as_user(text) to authenticated;

create function pg_temp.until(uid uuid) returns timestamptz language sql as $$
  select sync_until from librelog.accounts where user_id = uid
$$;

-- Clients can't record purchases themselves.
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-0000000000c1');
select pg_temp.expect_error($$select librelog.record_purchase('app_store', 'tx-1', '00000000-0000-0000-0000-0000000000c1', 'p', 'production', now() + interval '1 year', 'active', true, 'SUBSCRIBED', now())$$, '42501');
select pg_temp.expect_error($$insert into librelog.purchases (store, original_id, user_id, product_id, status) values ('play', 'x', '00000000-0000-0000-0000-0000000000c1', 'p', 'active')$$, '42501');
reset role;

-- A first purchase, recorded by the Edge Functions' service role, creates
-- the account and switches sync on.
set role service_role;
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('app_store', 'tx-1', '00000000-0000-0000-0000-0000000000c1', 'yearly',
         'production', '2030-01-01', 'active', true, 'SUBSCRIBED', '2029-01-01', 'evt-1');
  assert v = '2030-01-01'::timestamptz, 'sync_until after purchase: ' || v;
end $$;
reset role;
do $$ begin
  assert pg_temp.until('00000000-0000-0000-0000-0000000000c1') = '2030-01-01';
end $$;

-- A renewal moves it on; a redelivered notification and the same state
-- reported again by the app change nothing.
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2031-01-01', 'active', true, 'DID_RENEW', '2030-01-01', 'evt-2');
  assert v = '2031-01-01', 'after renewal: ' || v;
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2035-01-01', 'active', true, 'DID_RENEW', '2030-01-01', 'evt-2');
  assert v = '2031-01-01', 'redelivered event must be a no-op: ' || v;
  v := librelog.record_purchase('app_store', 'tx-1', '00000000-0000-0000-0000-0000000000c1', 'yearly',
         'production', '2031-01-01', 'active', true, 'VERIFY', '2030-01-01');
  assert v = '2031-01-01', 'idempotent verify: ' || v;
  assert (select count(*) from librelog.purchases where user_id = '00000000-0000-0000-0000-0000000000c1') = 1;
end $$;

-- An older event arriving late is ignored, and an expiry never moves
-- sync_until back.
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2030-01-01', 'expired', false, 'EXPIRED', '2029-06-01', 'evt-old');
  assert v = '2031-01-01', 'stale event: ' || v;
  assert (select status from librelog.purchases where original_id = 'tx-1') = 'active', 'stale event changed the row';
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2030-06-01', 'expired', false, 'EXPIRED', '2031-01-02', 'evt-3');
  assert v = '2031-01-01', 'expiry must not shorten: ' || v;
  assert (select status from librelog.purchases where original_id = 'tx-1') = 'expired';
end $$;

-- A purchase belongs to the account that made it.
select pg_temp.expect_error($$select librelog.record_purchase('app_store', 'tx-1', '00000000-0000-0000-0000-0000000000d1', 'yearly', 'production', '2032-01-01', 'active', true, 'VERIFY', '2031-02-01')$$, 'PT409');
-- A notification for a purchase nobody is on file for, or for no real user.
select pg_temp.expect_error($$select librelog.record_purchase('play', 'unknown', null, 'sync_yearly', 'production', '2032-01-01', 'active', true, 'RENEWED', now())$$, 'PT404');
select pg_temp.expect_error($$select librelog.record_purchase('play', 'tok-x', '00000000-0000-0000-0000-0000000000ff', 'sync_yearly', 'production', '2032-01-01', 'active', true, 'PURCHASED', now())$$, 'PT404');

-- Pending Play purchases are recorded but don't switch sync on.
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('play', 'tok-d', '00000000-0000-0000-0000-0000000000d1', 'sync_yearly',
         'production', null, 'pending', false, 'PURCHASED', '2029-01-01');
  assert v is null, 'pending must not grant: ' || coalesce(v::text, 'null');
  v := librelog.record_purchase('play', 'tok-d', '00000000-0000-0000-0000-0000000000d1', 'sync_yearly',
         'production', '2030-01-01', 'active', true, 'PURCHASED', '2029-01-02');
  assert v = '2030-01-01', 'after payment clears: ' || v;
end $$;

-- A refund takes back what that purchase paid for, but keeps a hand grant
-- and other purchases.
select librelog.admin_set_sync_until('dave@example.com', '2030-06-01');
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('play', 'tok-d', null, 'sync_yearly',
         'production', '2031-06-01', 'active', true, 'RENEWED', '2030-05-01');
  assert v = '2031-06-01', 'renewed: ' || v;
  v := librelog.record_purchase('play', 'tok-d', null, 'sync_yearly',
         'production', '2031-06-01', 'refunded', false, 'VOIDED', '2030-05-02', 'msg-9');
  assert v = '2030-06-01', 'refund should fall back to the hand grant: ' || v;
end $$;

-- With nothing else, a refund lapses the account now (pulls still work for
-- 30 days), and a reversal reinstates it.
do $$
declare v timestamptz;
begin
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2031-01-01', 'refunded', false, 'REFUND', '2031-01-03', 'evt-4');
  assert v <= now() and v > now() - interval '1 minute', 'refund should lapse now: ' || v;
  v := librelog.record_purchase('app_store', 'tx-1', null, 'yearly',
         'production', '2031-01-01', 'active', true, 'REFUND_REVERSED', '2031-01-04', 'evt-5');
  assert v = '2031-01-01', 'reversal: ' || v;
end $$;

-- The settings screen sees its own status and subscription only.
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-0000000000c1');
do $$
declare r record;
begin
  select * into r from librelog.sync_status();
  assert r.sync_until = '2031-01-01' and r.store = 'app_store' and r.status = 'active' and r.auto_renew,
    'status ' || row_to_json(r)::text;
  assert (select count(*) from librelog.purchases) = 1, 'carol should see only her purchase';
end $$;
select pg_temp.as_user('00000000-0000-0000-0000-0000000000a9');
do $$
declare r record;
begin
  select * into r from librelog.sync_status();
  assert r.sync_until is null and r.store is null, 'a user with no account has no status';
end $$;
select pg_temp.as_user('');
select pg_temp.expect_error($$select * from librelog.sync_status()$$, 'PT401');
reset role;

\o
\echo 'all billing tests passed'
