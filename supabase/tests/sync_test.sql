-- Behaviour tests for the sync schema. Run by run.sh after stubs.sql and the
-- migration. Any failed assertion aborts with an error.

\set ON_ERROR_STOP on
\o /dev/null

insert into auth.users (id, email) values
  ('00000000-0000-0000-0000-00000000000a', 'alice@example.com'),
  ('00000000-0000-0000-0000-00000000000b', 'bob@example.com');

-- Expect `sql` to fail with `code`.
create function pg_temp.expect_error(sql text, code text) returns void language plpgsql as $$
begin
  execute sql;
  raise exception 'expected % from: %', code, sql;
exception when others then
  if sqlstate <> code then
    raise exception 'expected %, got % (%) from: %', code, sqlstate, sqlerrm, sql;
  end if;
end $$;
grant execute on function pg_temp.expect_error(text, text) to authenticated;

create function pg_temp.as_user(uid text) returns void language sql as $$
  select set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, false)
$$;
grant execute on function pg_temp.as_user(text) to authenticated;

set role authenticated;

-- Anonymous calls are refused.
select pg_temp.as_user('');
select pg_temp.expect_error($$select dailylog.register_device(gen_random_uuid(), 'eA==')$$, 'PT401');

-- Alice registers two devices.
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
select * from dailylog.register_device('11111111-0000-0000-0000-000000000001', encode('iphone', 'base64'));
select * from dailylog.register_device('11111111-0000-0000-0000-000000000002', encode('mac', 'base64'));

-- Key check is set once; a second value doesn't overwrite it.
do $$ begin
  assert dailylog.set_key_check(encode('check-1', 'base64')) = encode('check-1', 'base64');
  assert dailylog.set_key_check(encode('check-2', 'base64')) = encode('check-1', 'base64');
end $$;

-- Unpaid accounts can't push.
select pg_temp.expect_error($$select dailylog.push_ops('11111111-0000-0000-0000-000000000001', gen_random_uuid(), array['b3Ax'])$$, 'PT402');

reset role;
select dailylog.admin_set_sync_until('alice@example.com', now() + interval '1 year');
set role authenticated;

-- Direct table writes are refused, even to your own rows.
select pg_temp.expect_error($$insert into dailylog.ops values ('00000000-0000-0000-0000-00000000000a', 99, gen_random_uuid(), gen_random_uuid(), 'x')$$, '42501');
select pg_temp.expect_error($$update dailylog.accounts set sync_until = now() + interval '10 years'$$, '42501');
select pg_temp.expect_error($$select * from dailylog.ops$$, '42501');

-- Pushes get gap-free seqs; a retried batch id is a no-op.
do $$
declare v bigint;
begin
  v := dailylog.push_ops('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', array[encode('op1','base64'), encode('op2','base64')]);
  assert v = 2, 'first push should end at 2, got ' || v;
  v := dailylog.push_ops('11111111-0000-0000-0000-000000000001', 'aaaaaaaa-0000-0000-0000-000000000001', array[encode('op1','base64'), encode('op2','base64')]);
  assert v = 2, 'retry should be a no-op, got ' || v;
  v := dailylog.push_ops('11111111-0000-0000-0000-000000000002', 'aaaaaaaa-0000-0000-0000-000000000002', array[encode('op3','base64')]);
  assert v = 3, 'second push should end at 3, got ' || v;
  assert (select bytes_used from dailylog.accounts) = 9, 'bytes_used should be 9';
end $$;

-- Pull returns ops in order, decoded payloads round-trip, and acks the cursor.
do $$
declare r record; seqs bigint[] := '{}'; texts text[] := '{}';
begin
  for r in select * from dailylog.pull_ops('11111111-0000-0000-0000-000000000002', 0) loop
    seqs := seqs || r.seq;
    texts := texts || convert_from(decode(r.payload, 'base64'), 'UTF8');
  end loop;
  assert seqs = array[1,2,3]::bigint[], 'seqs ' || seqs::text;
  assert texts = array['op1','op2','op3'], 'payloads ' || texts::text;
  perform * from dailylog.pull_ops('11111111-0000-0000-0000-000000000002', 3);
  assert (select acked_seq from dailylog.devices where id = '11111111-0000-0000-0000-000000000002') = 3;
end $$;

-- A push pinged the account's private channel.
reset role;
do $$ begin
  assert (select count(*) from realtime.messages where topic = 'user:00000000-0000-0000-0000-00000000000a') = 2;
end $$;
set role authenticated;

-- Long payloads come back without base64 line breaks.
do $$
declare v bigint; p text;
begin
  v := dailylog.push_ops('11111111-0000-0000-0000-000000000001', gen_random_uuid(), array[encode(repeat('x', 300)::bytea, 'base64')]);
  select payload into p from dailylog.pull_ops('11111111-0000-0000-0000-000000000001', v - 1);
  assert position(E'\n' in p) = 0, 'payload contains a newline';
  assert octet_length(decode(p, 'base64')) = 300;
end $$;

-- Bob can't see or use Alice's devices, and sees no account of hers.
select pg_temp.as_user('00000000-0000-0000-0000-00000000000b');
select pg_temp.expect_error($$select dailylog.pull_ops('11111111-0000-0000-0000-000000000001', 0)$$, 'PT404');
select pg_temp.expect_error($$select * from dailylog.register_device('11111111-0000-0000-0000-000000000001', 'eA==')$$, 'PT400');
do $$ begin
  assert (select count(*) from dailylog.devices) = 0, 'bob sees alice''s devices';
  assert (select count(*) from dailylog.accounts) = 0, 'bob has no account yet and must not see alice''s';
end $$;
-- Bob's account exists now but is unpaid, so he can't pull either.
select * from dailylog.register_device('22222222-0000-0000-0000-000000000001', encode('pixel', 'base64'));
select pg_temp.expect_error($$select dailylog.pull_ops('22222222-0000-0000-0000-000000000001', 0)$$, 'PT402');

-- Pairing: the new device opens, an existing device fulfils, the new device takes once.
select pg_temp.as_user('00000000-0000-0000-0000-00000000000a');
do $$
begin
  perform dailylog.pairing_open('cccccccc-0000-0000-0000-000000000001');
  assert dailylog.pairing_take('cccccccc-0000-0000-0000-000000000001') is null, 'not fulfilled yet';
  perform dailylog.pairing_fulfil('cccccccc-0000-0000-0000-000000000001', encode('wrapped-key', 'base64'));
  assert dailylog.pairing_take('cccccccc-0000-0000-0000-000000000001') = encode('wrapped-key', 'base64');
end $$;
select pg_temp.expect_error($$select dailylog.pairing_take('cccccccc-0000-0000-0000-000000000001')$$, 'PT404');

-- Snapshot + compaction: ops at or below the snapshot go once every active
-- device has acked past it, and a cursor behind them gets 409.
do $$
declare last bigint;
begin
  select last_seq into last from dailylog.accounts;
  perform dailylog.put_snapshot('11111111-0000-0000-0000-000000000001', last, encode('snapshot', 'base64'));
  perform * from dailylog.pull_ops('11111111-0000-0000-0000-000000000001', last);
  perform * from dailylog.pull_ops('11111111-0000-0000-0000-000000000002', last);
end $$;
reset role;
select dailylog.maintenance();
do $$ begin
  assert (select count(*) from dailylog.ops) = 0, 'ops should be compacted';
  assert (select bytes_used from dailylog.accounts where user_id = '00000000-0000-0000-0000-00000000000a') = 8, 'only the snapshot remains';
end $$;
set role authenticated;
select pg_temp.expect_error($$select dailylog.pull_ops('11111111-0000-0000-0000-000000000002', 0)$$, 'PT409');
do $$ begin
  assert (select payload from dailylog.get_snapshot('11111111-0000-0000-0000-000000000002')) = encode('snapshot', 'base64');
end $$;

-- Revoked devices can't push.
select dailylog.revoke_device('11111111-0000-0000-0000-000000000002');
select pg_temp.expect_error($$select dailylog.push_ops('11111111-0000-0000-0000-000000000002', gen_random_uuid(), array['eA=='])$$, 'PT404');

-- Anon and authenticated can't call internals or the admin function.
select pg_temp.expect_error($$select dailylog.maintenance()$$, '42501');
select pg_temp.expect_error($$select dailylog.admin_set_sync_until('alice@example.com', now() + interval '99 years')$$, '42501');
set role anon;
select pg_temp.expect_error($$select dailylog.pull_ops('11111111-0000-0000-0000-000000000001', 0)$$, '42501');

-- Deleting the account removes everything.
set role authenticated;
select dailylog.delete_account();
reset role;
do $$ begin
  assert (select count(*) from dailylog.accounts where user_id = '00000000-0000-0000-0000-00000000000a') = 0;
  assert (select count(*) from dailylog.snapshots) = 0;
  assert (select count(*) from dailylog.devices where user_id = '00000000-0000-0000-0000-00000000000a') = 0;
end $$;

\o
\echo 'all sync tests passed'
