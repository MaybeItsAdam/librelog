-- Behaviour tests for web purchases through Stripe (record_stripe_purchase).
-- Run by run.sh after the migrations. Any failed assertion aborts with an
-- error.

\set ON_ERROR_STOP on
\o /dev/null

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-0000000000e1', 'erin@example.com'),
  ('00000000-0000-0000-0000-0000000000f1', 'frank@example.com'),
  ('00000000-0000-0000-0000-0000000000f2', 'grace@example.com');

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

-- Within a minute of: each statement has its own now().
create function pg_temp.near(a timestamptz, b timestamptz) returns boolean language sql as $$
  select a is not null and b is not null and abs(extract(epoch from a - b)) < 60
$$;
grant execute on function pg_temp.near(timestamptz, timestamptz) to service_role;

-- Clients can't record web purchases themselves.
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-0000000000e1');
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_x', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production', 1, 'active', 'checkout.session.completed', now())$$, '42501');
reset role;

-- A first purchase, recorded by the Edge Function's service role, buys a
-- year from now and creates the account.
set role service_role;
do $$
declare v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_1', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production',
         1, 'active', 'checkout.session.completed', now(), 'evt_1');
  assert pg_temp.near(v, now() + interval '1 year'), 'first purchase: ' || v;
end $$;
reset role;
do $$
declare p librelog.purchases;
begin
  select * into p from librelog.purchases where store = 'stripe' and original_id = 'pi_1';
  assert p.user_id = '00000000-0000-0000-0000-0000000000e1' and p.status = 'active' and not p.auto_renew
    and p.product_id = 'sync_1y', 'row ' || row_to_json(p)::text;
  assert pg_temp.near(p.starts_at, now()), 'starts now: ' || p.starts_at;
  assert p.expires_at = (select sync_until from librelog.accounts where user_id = p.user_id);
end $$;

-- The same payment again, as a redelivery or as the other "paid" event,
-- grants nothing more.
do $$
declare
  before timestamptz := (select sync_until from librelog.accounts where user_id = '00000000-0000-0000-0000-0000000000e1');
  v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_1', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production',
         1, 'active', 'checkout.session.completed', now(), 'evt_1');
  assert v = before, 'redelivered event: ' || v;
  v := librelog.record_stripe_purchase('pi_1', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production',
         1, 'active', 'checkout.session.async_payment_succeeded', now(), 'evt_1b');
  assert v = before, 'same payment, another event: ' || v;
end $$;

-- Buying again early stacks on what's left: three more years from the
-- current paid-through date, not from now.
do $$
declare
  before timestamptz := (select sync_until from librelog.accounts where user_id = '00000000-0000-0000-0000-0000000000e1');
  v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_2', '00000000-0000-0000-0000-0000000000e1', 'sync_3y', 'production',
         3, 'active', 'checkout.session.completed', now(), 'evt_2');
  assert v = before + interval '3 years', 'stacked: ' || v || ' after ' || before;
  assert (select starts_at from librelog.purchases where original_id = 'pi_2') = before, 'pi_2 starts when pi_1 ends';
end $$;

-- Refunding the first takes back its year, and the three years stacked
-- after it move up to start now.
do $$
declare v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_1', null, null, null, null, 'refunded', 'charge.refunded', now(), 'evt_3');
  assert pg_temp.near(v, now() + interval '3 years'), 'after refunding the first: ' || v;
  assert pg_temp.near((select starts_at from librelog.purchases where original_id = 'pi_2'), now()), 'pi_2 moved up';
  assert (select status from librelog.purchases where original_id = 'pi_1') = 'refunded';
end $$;

-- A dispute on the refunded payment, and the payment's "paid" event
-- arriving again, change nothing.
do $$
declare
  before timestamptz := (select sync_until from librelog.accounts where user_id = '00000000-0000-0000-0000-0000000000e1');
  v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_1', null, null, null, null, 'revoked', 'charge.dispute.created', now(), 'evt_4');
  assert v = before, 'dispute after refund: ' || v;
  v := librelog.record_stripe_purchase('pi_1', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production',
         1, 'active', 'checkout.session.completed', now(), 'evt_5');
  assert v = before, 'a refunded payment is not granted again: ' || v;
  assert (select status from librelog.purchases where original_id = 'pi_1') = 'refunded';
end $$;

-- A dispute on the last one leaves nothing but the 30 days of pulls.
do $$
declare v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_2', null, null, null, null, 'revoked', 'charge.dispute.created', now(), 'evt_6');
  assert v <= now() and v > now() - interval '1 minute', 'dispute should lapse now: ' || v;
end $$;

-- An account already paid ahead (a hand grant here) buys time from the end
-- of it; refunding a purchase stacked last moves nothing else and falls back
-- to the grant.
select librelog.admin_set_sync_until('frank@example.com', '2040-01-01');
do $$
declare v timestamptz;
begin
  v := librelog.record_stripe_purchase('pi_f1', '00000000-0000-0000-0000-0000000000f1', 'sync_1y', 'production',
         1, 'active', 'checkout.session.completed', now(), 'evt_f1');
  assert v = '2041-01-01', 'from the end of the grant: ' || v;
  v := librelog.record_stripe_purchase('pi_f2', '00000000-0000-0000-0000-0000000000f1', 'sync_3y', 'production',
         3, 'active', 'checkout.session.completed', now(), 'evt_f2');
  assert v = '2044-01-01', 'stacked again: ' || v;
  v := librelog.record_stripe_purchase('pi_f2', null, null, null, null, 'refunded', 'charge.refunded', now(), 'evt_f3');
  assert v = '2041-01-01', 'refund of the last: ' || v;
  assert (select expires_at from librelog.purchases where original_id = 'pi_f1') = '2041-01-01', 'pi_f1 untouched';
end $$;

-- Test-mode purchases are kept apart by environment.
do $$
begin
  perform librelog.record_stripe_purchase('cs_test_free', '00000000-0000-0000-0000-0000000000f2', 'sync_1y', 'sandbox',
            1, 'active', 'checkout.session.completed', now(), 'evt_g1');
  assert (select environment from librelog.purchases where original_id = 'cs_test_free') = 'sandbox';
end $$;

-- Refusals.
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_nobody', null, null, null, null, 'refunded', 'charge.refunded', now(), 'evt_x1')$$, 'PT404');
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_ghost', '00000000-0000-0000-0000-0000000000ff', 'sync_1y', 'production', 1, 'active', 'checkout.session.completed', now(), 'evt_x2')$$, 'PT404');
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_f1', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production', 1, 'active', 'checkout.session.completed', now(), 'evt_x3')$$, 'PT409');
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_bad', '00000000-0000-0000-0000-0000000000e1', 'sync_0y', 'production', 0, 'active', 'checkout.session.completed', now(), 'evt_x4')$$, 'PT400');
select pg_temp.expect_error($$select librelog.record_stripe_purchase('pi_bad', '00000000-0000-0000-0000-0000000000e1', 'sync_1y', 'production', 1, 'expired', 'x', now(), 'evt_x5')$$, 'PT400');

-- The settings screen sees a web purchase as such.
set role authenticated;
select pg_temp.as_user('00000000-0000-0000-0000-0000000000f1');
do $$
declare r record;
begin
  select * into r from librelog.sync_status();
  assert r.sync_until = '2041-01-01' and r.store = 'stripe' and r.status = 'active' and not r.auto_renew,
    'status ' || row_to_json(r)::text;
end $$;
reset role;

\o
\echo 'all stripe tests passed'
