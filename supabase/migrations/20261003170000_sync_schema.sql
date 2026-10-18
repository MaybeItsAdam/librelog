-- Librelog text sync. SPEC.md §12.5 is the design; supabase/README.md is the
-- client contract.
--
-- The server stores ciphertext only: every op, snapshot, device name and
-- pairing payload is encrypted on the device before it gets here. Clients
-- never write tables directly. Every write goes through a security definer
-- function below, and clients can read only through those functions plus
-- the select policies on `accounts` and `devices`.
--
-- Errors use PostgREST's PTxxx SQLSTATEs so the HTTP status says what went
-- wrong: 401 not signed in, 402 sync not paid for, 404 unknown device or
-- pairing, 409 cursor behind compaction, 413 over quota or batch too big,
-- 400 bad input.

create schema if not exists librelog;

grant usage on schema librelog to authenticated, service_role;

-- Tables --------------------------------------------------------------------

create table librelog.accounts (
  user_id     uuid primary key references auth.users (id) on delete cascade,
  -- Highest seq handed out. Gap-free: push_ops takes this row's lock.
  last_seq    bigint not null default 0,
  -- Ciphertext bytes held in ops + snapshots, counted against quota_bytes.
  bytes_used  bigint not null default 0,
  quota_bytes bigint not null default 524288000, -- 500 MB
  -- Paid through. Null = never paid; pushes need this in the future, pulls
  -- are allowed until 30 days after it.
  sync_until  timestamptz,
  -- A constant encrypted under the journal key, so a newly paired device
  -- can tell it was handed the right key before it syncs anything.
  key_check   bytea,
  created_at  timestamptz not null default now()
);

create table librelog.devices (
  id          uuid primary key,
  user_id     uuid not null references librelog.accounts (user_id) on delete cascade,
  name_enc    bytea not null,
  -- The device has applied every op up to here. Compaction waits for it.
  acked_seq   bigint not null default 0,
  last_seen   timestamptz not null default now(),
  revoked_at  timestamptz,
  created_at  timestamptz not null default now()
);

create index devices_user_id on librelog.devices (user_id);

create table librelog.ops (
  user_id     uuid not null references librelog.accounts (user_id) on delete cascade,
  seq         bigint not null,
  device_id   uuid not null,
  -- The client's id for the push, so a retried push is not stored twice.
  batch_id    uuid not null,
  payload     bytea not null,
  created_at  timestamptz not null default now(),
  primary key (user_id, seq)
);

create index ops_batch on librelog.ops (user_id, batch_id);

create table librelog.snapshots (
  user_id     uuid not null references librelog.accounts (user_id) on delete cascade,
  -- The snapshot covers every op with seq <= this.
  seq         bigint not null,
  device_id   uuid not null,
  payload     bytea not null,
  created_at  timestamptz not null default now(),
  primary key (user_id, seq)
);

create table librelog.pairings (
  id          uuid primary key,
  user_id     uuid not null references librelog.accounts (user_id) on delete cascade,
  -- The journal key, encrypted by an existing device to the new device's
  -- ephemeral X25519 key (which travels only in the QR code).
  payload     bytea,
  expires_at  timestamptz not null
);

create index pairings_user_id on librelog.pairings (user_id);

-- Row-level security ----------------------------------------------------------
-- Select-only, own rows only. There are deliberately no insert, update or
-- delete policies: writes go through the functions below.

alter table librelog.accounts  enable row level security;
alter table librelog.devices   enable row level security;
alter table librelog.ops       enable row level security;
alter table librelog.snapshots enable row level security;
alter table librelog.pairings  enable row level security;

revoke all on all tables in schema librelog from anon, authenticated;

grant select on librelog.accounts, librelog.devices to authenticated;

create policy "own account" on librelog.accounts
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy "own devices" on librelog.devices
  for select to authenticated
  using (user_id = (select auth.uid()));

-- Helpers ---------------------------------------------------------------------

-- The signed-in user, or 401.
create function librelog.require_user()
returns uuid
language plpgsql
stable
set search_path = ''
as $$
declare
  v_user uuid := auth.uid();
begin
  if v_user is null then
    raise exception 'not signed in' using errcode = 'PT401';
  end if;
  return v_user;
end;
$$;

-- The caller's live device, or 404.
create function librelog.require_device(p_user uuid, p_device_id uuid)
returns void
language plpgsql
stable
set search_path = ''
as $$
begin
  perform 1
  from librelog.devices
  where id = p_device_id and user_id = p_user and revoked_at is null;

  if not found then
    raise exception 'unknown device' using errcode = 'PT404';
  end if;
end;
$$;

-- base64 without the line breaks Postgres puts every 76 characters.
create function librelog.b64(p_bytes bytea)
returns text
language sql
immutable
set search_path = ''
as $$
  select translate(encode(p_bytes, 'base64'), E'\n', '');
$$;

-- Devices ---------------------------------------------------------------------

-- First call on every device after sign-in. Creates the account on first use
-- and registers (or renames) the device. Returns the account's state.
create function librelog.register_device(p_device_id uuid, p_name_enc text)
returns table (last_seq bigint, sync_until timestamptz, key_check text, quota_bytes bigint, bytes_used bigint)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_owner uuid;
begin
  insert into librelog.accounts (user_id)
  values (v_user)
  on conflict (user_id) do nothing;

  select d.user_id into v_owner from librelog.devices d where d.id = p_device_id;
  if v_owner is not null and v_owner <> v_user then
    raise exception 'device belongs to another account' using errcode = 'PT400';
  end if;

  insert into librelog.devices (id, user_id, name_enc)
  values (p_device_id, v_user, decode(p_name_enc, 'base64'))
  on conflict (id) do update
    set name_enc = excluded.name_enc, last_seen = now(), revoked_at = null;

  return query
  select a.last_seq, a.sync_until, librelog.b64(a.key_check), a.quota_bytes, a.bytes_used
  from librelog.accounts a
  where a.user_id = v_user;
end;
$$;

-- Sets the key check once, from the first device. Returns whatever is stored,
-- so a device racing another learns the winner's value.
create function librelog.set_key_check(p_key_check text)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_check bytea;
begin
  update librelog.accounts
  set key_check = decode(p_key_check, 'base64')
  where user_id = v_user and key_check is null;

  select key_check into v_check from librelog.accounts where user_id = v_user;
  if not found then
    raise exception 'no account; call register_device first' using errcode = 'PT404';
  end if;
  return librelog.b64(v_check);
end;
$$;

create function librelog.revoke_device(p_device_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
begin
  update librelog.devices
  set revoked_at = now()
  where id = p_device_id and user_id = v_user;

  if not found then
    raise exception 'unknown device' using errcode = 'PT404';
  end if;
end;
$$;

-- Ops -------------------------------------------------------------------------

-- Appends a batch of encrypted ops and returns the account's new last_seq.
-- Serialised per account by the row lock on accounts, so seqs are gap-free
-- and in commit order. Retrying with the same batch id is a no-op.
create function librelog.push_ops(p_device_id uuid, p_batch_id uuid, p_payloads text[])
returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_count int := coalesce(array_length(p_payloads, 1), 0);
  v_blobs bytea[];
  v_bytes bigint;
  v_account librelog.accounts;
  v_last bigint;
begin
  perform librelog.require_device(v_user, p_device_id);

  if v_count = 0 then
    raise exception 'empty batch' using errcode = 'PT400';
  end if;
  if v_count > 500 then
    raise exception 'batch over 500 ops' using errcode = 'PT413';
  end if;

  select * into v_account
  from librelog.accounts
  where user_id = v_user
  for update;

  if v_account.sync_until is null or v_account.sync_until < now() then
    raise exception 'sync is not active for this account' using errcode = 'PT402';
  end if;

  if exists (select 1 from librelog.ops where user_id = v_user and batch_id = p_batch_id) then
    return v_account.last_seq;
  end if;

  select array_agg(decode(p, 'base64') order by i), sum(octet_length(decode(p, 'base64')))
  into v_blobs, v_bytes
  from unnest(p_payloads) with ordinality as t (p, i);

  if v_account.bytes_used + v_bytes > v_account.quota_bytes then
    raise exception 'over storage quota' using errcode = 'PT413';
  end if;

  update librelog.accounts
  set last_seq = last_seq + v_count, bytes_used = bytes_used + v_bytes
  where user_id = v_user
  returning last_seq into v_last;

  insert into librelog.ops (user_id, seq, device_id, batch_id, payload)
  select v_user, v_last - v_count + i, p_device_id, p_batch_id, b
  from unnest(v_blobs) with ordinality as t (b, i);

  update librelog.devices set last_seen = now() where id = p_device_id;

  -- Wake the account's other online devices; delivered after commit. Skipped
  -- if Realtime hasn't initialised its schema yet (a brand-new project):
  -- devices still sync on launch, foreground and background fetch.
  if to_regprocedure('realtime.send(jsonb, text, text, boolean)') is not null then
    perform realtime.send(
      jsonb_build_object('seq', v_last, 'device_id', p_device_id),
      'ops',
      'user:' || v_user::text,
      true
    );
  end if;

  return v_last;
end;
$$;

-- Ops after the cursor, oldest first. Calling with p_after also acknowledges
-- that this device has applied everything up to p_after. 409 means the ops
-- right after the cursor were compacted away: load the snapshot, then pull
-- from its seq.
create function librelog.pull_ops(p_device_id uuid, p_after bigint, p_limit int default 500)
returns table (seq bigint, device_id uuid, payload text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_account librelog.accounts;
begin
  perform librelog.require_device(v_user, p_device_id);

  select * into v_account from librelog.accounts where user_id = v_user;

  if v_account.sync_until is null or v_account.sync_until + interval '30 days' < now() then
    raise exception 'sync is not active for this account' using errcode = 'PT402';
  end if;

  update librelog.devices
  set acked_seq = greatest(acked_seq, least(p_after, v_account.last_seq)), last_seen = now()
  where id = p_device_id;

  if p_after < v_account.last_seq and not exists (
    select 1 from librelog.ops o where o.user_id = v_user and o.seq = p_after + 1
  ) then
    raise exception 'cursor is behind compaction; load the snapshot' using errcode = 'PT409';
  end if;

  return query
  select o.seq, o.device_id, librelog.b64(o.payload)
  from librelog.ops o
  where o.user_id = v_user and o.seq > p_after
  order by o.seq
  limit least(greatest(p_limit, 1), 1000);
end;
$$;

-- Snapshots ---------------------------------------------------------------------

-- Stores an encrypted snapshot of the whole journal as of p_seq. Older
-- snapshots beyond the newest two are dropped; the ops they cover are
-- removed by maintenance() once every active device has passed them.
create function librelog.put_snapshot(p_device_id uuid, p_seq bigint, p_payload text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_account librelog.accounts;
  v_blob bytea := decode(p_payload, 'base64');
begin
  perform librelog.require_device(v_user, p_device_id);

  select * into v_account
  from librelog.accounts
  where user_id = v_user
  for update;

  if v_account.sync_until is null or v_account.sync_until < now() then
    raise exception 'sync is not active for this account' using errcode = 'PT402';
  end if;
  if p_seq < 0 or p_seq > v_account.last_seq then
    raise exception 'snapshot seq is beyond the log' using errcode = 'PT400';
  end if;
  if v_account.bytes_used + octet_length(v_blob) > v_account.quota_bytes then
    raise exception 'over storage quota' using errcode = 'PT413';
  end if;

  insert into librelog.snapshots (user_id, seq, device_id, payload)
  values (v_user, p_seq, p_device_id, v_blob)
  on conflict (user_id, seq) do update
    set payload = excluded.payload, device_id = excluded.device_id, created_at = now();

  delete from librelog.snapshots s
  where s.user_id = v_user
    and s.seq < (
      select min(k.seq) from (
        select seq from librelog.snapshots where user_id = v_user order by seq desc limit 2
      ) k
    );

  perform librelog.recount_bytes(v_user);
end;
$$;

-- The newest snapshot, or no row if there isn't one.
create function librelog.get_snapshot(p_device_id uuid)
returns table (seq bigint, payload text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
begin
  perform librelog.require_device(v_user, p_device_id);

  return query
  select s.seq, librelog.b64(s.payload)
  from librelog.snapshots s
  where s.user_id = v_user
  order by s.seq desc
  limit 1;
end;
$$;

-- Pairing -----------------------------------------------------------------------
-- New device: pairing_open(id), show QR {id, X25519 public key}, then poll
-- pairing_take(id). Existing device: scan, encrypt the journal key to the
-- public key, pairing_fulfil(id, payload). Both are signed in to the same
-- account; the server never sees the key in the clear.

create function librelog.pairing_open(p_id uuid)
returns timestamptz
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_expires timestamptz := now() + interval '10 minutes';
begin
  insert into librelog.accounts (user_id) values (v_user) on conflict (user_id) do nothing;

  insert into librelog.pairings (id, user_id, expires_at)
  values (p_id, v_user, v_expires);

  return v_expires;
end;
$$;

create function librelog.pairing_fulfil(p_id uuid, p_payload text)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
begin
  update librelog.pairings
  set payload = decode(p_payload, 'base64')
  where id = p_id and user_id = v_user and payload is null and expires_at > now();

  if not found then
    raise exception 'unknown or expired pairing' using errcode = 'PT404';
  end if;
end;
$$;

-- Returns the payload and deletes the pairing once it has one; null while
-- the existing device hasn't answered yet.
create function librelog.pairing_take(p_id uuid)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
  v_payload bytea;
begin
  select payload into v_payload
  from librelog.pairings
  where id = p_id and user_id = v_user and expires_at > now();

  if not found then
    raise exception 'unknown or expired pairing' using errcode = 'PT404';
  end if;

  if v_payload is not null then
    delete from librelog.pairings where id = p_id;
  end if;

  return librelog.b64(v_payload);
end;
$$;

-- Account -----------------------------------------------------------------------

-- Deletes the signed-in user and, by cascade, everything they stored.
create function librelog.delete_account()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
begin
  delete from auth.users where id = v_user;
end;
$$;

-- Maintenance -------------------------------------------------------------------

create function librelog.recount_bytes(p_user uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update librelog.accounts
  set bytes_used =
      coalesce((select sum(octet_length(payload)) from librelog.ops where user_id = p_user), 0)
    + coalesce((select sum(octet_length(payload)) from librelog.snapshots where user_id = p_user), 0)
  where user_id = p_user;
$$;

-- Daily, from pg_cron. Drops expired pairings, compacts each account's log
-- below its newest snapshot once every device seen in the last 90 days has
-- applied past it, and deletes accounts lapsed for more than 90 days.
create function librelog.maintenance()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row record;
begin
  delete from librelog.pairings where expires_at < now();

  delete from librelog.accounts
  where sync_until < now() - interval '90 days';

  for v_row in
    select s.user_id, max(s.seq) as snap_seq
    from librelog.snapshots s
    group by s.user_id
  loop
    if v_row.snap_seq <= coalesce((
      select min(d.acked_seq)
      from librelog.devices d
      where d.user_id = v_row.user_id
        and d.revoked_at is null
        and d.last_seen > now() - interval '90 days'
    ), v_row.snap_seq) then
      delete from librelog.ops
      where user_id = v_row.user_id and seq <= v_row.snap_seq;

      perform librelog.recount_bytes(v_row.user_id);
    end if;
  end loop;
end;
$$;

-- Sets how long an account may sync. Called by the payment webhooks with the
-- service role, and by hand from the SQL editor while there is no checkout.
create function librelog.admin_set_sync_until(p_email text, p_until timestamptz)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user uuid;
begin
  select id into v_user from auth.users where email = lower(p_email);
  if v_user is null then
    raise exception 'no user with that email' using errcode = 'PT404';
  end if;

  insert into librelog.accounts (user_id, sync_until)
  values (v_user, p_until)
  on conflict (user_id) do update set sync_until = excluded.sync_until;
end;
$$;

-- Function privileges -------------------------------------------------------------
-- Postgres grants execute to PUBLIC by default; take that back, then grant
-- the client API to signed-in users only.

revoke execute on all functions in schema librelog from public, anon, authenticated;

grant execute on function
  librelog.register_device(uuid, text),
  librelog.set_key_check(text),
  librelog.revoke_device(uuid),
  librelog.push_ops(uuid, uuid, text[]),
  librelog.pull_ops(uuid, bigint, int),
  librelog.put_snapshot(uuid, bigint, text),
  librelog.get_snapshot(uuid),
  librelog.pairing_open(uuid),
  librelog.pairing_fulfil(uuid, text),
  librelog.pairing_take(uuid),
  librelog.delete_account()
to authenticated;

grant execute on function librelog.admin_set_sync_until(text, timestamptz) to service_role;

-- Nightly maintenance (§12.5) ---------------------------------------------------
-- cron.schedule replaces an existing job with the same name.

create extension if not exists pg_cron;

select cron.schedule(
  'librelog-maintenance',
  '17 3 * * *',
  $$select librelog.maintenance()$$
);

-- Realtime ----------------------------------------------------------------------
-- Lets each signed-in device receive (never send) on its own private
-- broadcast channel, `user:<uid>`, which push_ops pings after every push.
--
-- Realtime creates realtime.messages the first time the service starts for a
-- project, which can be after this migration first runs. If it's missing,
-- this stops `supabase db push` with instructions and the whole migration
-- rolls back; just re-run the push afterwards.

do $$
begin
  if to_regclass('realtime.messages') is null then
    raise exception 'realtime.messages does not exist yet'
      using hint = 'Open Realtime in the Supabase dashboard once (it initialises the schema), then run supabase db push again.';
  end if;
end $$;

create policy "librelog: receive own sync pings" on realtime.messages
  for select to authenticated
  using (
    realtime.messages.extension = 'broadcast'
    and (select realtime.topic()) = 'user:' || (select auth.uid())::text
  );
