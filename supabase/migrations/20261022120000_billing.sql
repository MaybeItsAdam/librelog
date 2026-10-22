-- Sync subscriptions (SPEC.md §15). docs/billing.md has the store setup.
--
-- Store purchases are verified by the Edge Functions in supabase/functions
-- (App Store Server Notifications, Google Play RTDN, and the apps reporting a
-- fresh purchase), which call record_purchase() with the service role. Each
-- purchase is one row, keyed by the store's id for the subscription, so its
-- renewals, lapses and refunds land on the account that bought it.
--
-- accounts.sync_until stays the one thing push_ops and pull_ops check. A
-- verified renewal only ever moves it later; a refund or revocation is the
-- one event that moves it back, to whatever the account's other purchases
-- and any hand grant still cover.

-- Tables --------------------------------------------------------------------

create table librelog.purchases (
  -- 'app_store', 'play' (and 'stripe' once web checkout exists).
  store         text not null check (store in ('app_store', 'play', 'stripe')),
  -- App Store: originalTransactionId, the same across every renewal.
  -- Play: the purchase token, the same across renewals (a resubscribe gets
  -- a new one).
  original_id   text not null,
  user_id       uuid not null references auth.users (id) on delete cascade,
  product_id    text not null,
  -- 'production' or 'sandbox' (App Review, TestFlight, licence testers).
  environment   text not null default 'production',
  -- Paid through, including any billing grace period the store gives.
  expires_at    timestamptz,
  status        text not null check (status in (
                  'active', 'grace', 'billing_retry', 'paused', 'pending',
                  'expired', 'refunded', 'revoked')),
  auto_renew    boolean not null default false,
  -- The store event that last changed this row, and when the store says it
  -- happened. Older events arriving late are ignored.
  last_event    text,
  last_event_at timestamptz,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now(),
  primary key (store, original_id)
);

create index purchases_user_id on librelog.purchases (user_id);

-- Store notifications already applied, so a redelivered one is a no-op.
create table librelog.billing_events (
  store       text not null,
  -- App Store notificationUUID, Pub/Sub messageId.
  event_id    text not null,
  received_at timestamptz not null default now(),
  primary key (store, event_id)
);

-- Sync granted by hand (admin_set_sync_until), kept apart from purchases so
-- a refund can't take back a grant, and a grant can't hide a refund.
alter table librelog.accounts add column granted_until timestamptz;

alter table librelog.purchases      enable row level security;
alter table librelog.billing_events enable row level security;

revoke all on librelog.purchases, librelog.billing_events from anon, authenticated;

-- The apps show which store a subscription is with and whether it renews.
grant select on librelog.purchases to authenticated;

create policy "own purchases" on librelog.purchases
  for select to authenticated
  using (user_id = (select auth.uid()));

-- Recording purchases ---------------------------------------------------------

-- Applies one verified store state to a purchase and to its account's
-- sync_until, and returns the account's sync_until afterwards.
--
-- p_user is who the store says bought it (appAccountToken on the App Store,
-- obfuscatedExternalAccountId on Play). It may be null for a notification
-- about a purchase already on file; a purchase nobody can be found for is
-- 404. A purchase already on file for another account is 409: one
-- subscription syncs one account.
--
-- p_event_id makes a redelivered notification a no-op; p_event_at (when the
-- store signed the state) makes a late, older one a no-op.
create function librelog.record_purchase(
  p_store       text,
  p_original_id text,
  p_user        uuid,
  p_product_id  text,
  p_environment text,
  p_expires_at  timestamptz,
  p_status      text,
  p_auto_renew  boolean,
  p_event       text,
  p_event_at    timestamptz,
  p_event_id    text default null
)
returns timestamptz
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_existing librelog.purchases;
  v_user uuid;
  v_until timestamptz;
begin
  if p_original_id is null or p_original_id = '' then
    raise exception 'missing purchase id' using errcode = 'PT400';
  end if;

  select * into v_existing
  from librelog.purchases
  where store = p_store and original_id = p_original_id
  for update;

  v_user := coalesce(v_existing.user_id, p_user);
  if v_user is null then
    raise exception 'purchase is not linked to an account' using errcode = 'PT404';
  end if;
  if v_existing.user_id is not null and p_user is not null and p_user <> v_existing.user_id then
    raise exception 'purchase belongs to another account' using errcode = 'PT409';
  end if;
  if not exists (select 1 from auth.users where id = v_user) then
    raise exception 'no such user' using errcode = 'PT404';
  end if;

  insert into librelog.accounts (user_id) values (v_user) on conflict (user_id) do nothing;

  -- Serialise with push_ops and with other events for this account.
  select sync_until into v_until from librelog.accounts where user_id = v_user for update;

  if p_event_id is not null then
    insert into librelog.billing_events (store, event_id)
    values (p_store, p_event_id)
    on conflict do nothing;
    if not found then
      return v_until; -- already applied
    end if;
  end if;

  if v_existing.last_event_at is not null and p_event_at < v_existing.last_event_at then
    return v_until; -- older than what's on file
  end if;

  insert into librelog.purchases as p (
    store, original_id, user_id, product_id, environment, expires_at, status,
    auto_renew, last_event, last_event_at
  )
  values (
    p_store, p_original_id, v_user, p_product_id, coalesce(p_environment, 'production'),
    p_expires_at, p_status, coalesce(p_auto_renew, false), p_event, p_event_at
  )
  on conflict (store, original_id) do update
    set product_id    = excluded.product_id,
        environment   = excluded.environment,
        expires_at    = excluded.expires_at,
        status        = excluded.status,
        auto_renew    = excluded.auto_renew,
        last_event    = excluded.last_event,
        last_event_at = excluded.last_event_at,
        updated_at    = now();

  if p_status in ('refunded', 'revoked') then
    -- Take back what this purchase paid for, keeping what the account's other
    -- live purchases and any hand grant cover. Lapsing now rather than
    -- clearing sync_until leaves the usual 30 days to pull.
    update librelog.accounts a
    set sync_until = greatest(
          (select max(p.expires_at)
           from librelog.purchases p
           where p.user_id = v_user
             and p.status not in ('refunded', 'revoked', 'pending')),
          a.granted_until,
          least(a.sync_until, now())
        )
    where a.user_id = v_user
    returning a.sync_until into v_until;
  elsif p_expires_at is not null and p_status not in ('pending') then
    update librelog.accounts a
    set sync_until = greatest(a.sync_until, p_expires_at)
    where a.user_id = v_user
    returning a.sync_until into v_until;
  end if;

  return v_until;
end;
$$;

-- Hand grants also record granted_until, so a later refund keeps them.
create or replace function librelog.admin_set_sync_until(p_email text, p_until timestamptz)
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

  insert into librelog.accounts (user_id, sync_until, granted_until)
  values (v_user, p_until, p_until)
  on conflict (user_id) do update
    set sync_until = excluded.sync_until, granted_until = excluded.granted_until;
end;
$$;

-- The signed-in user's sync status for the settings screen: paid-through
-- date and the subscription that pays for it, if any.
create function librelog.sync_status()
returns table (
  sync_until timestamptz,
  store      text,
  product_id text,
  status     text,
  expires_at timestamptz,
  auto_renew boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user uuid := librelog.require_user();
begin
  return query
  select a.sync_until, p.store, p.product_id, p.status, p.expires_at, p.auto_renew
  from (select v_user as user_id) u
  left join librelog.accounts a on a.user_id = u.user_id
  left join lateral (
    select * from librelog.purchases p
    where p.user_id = u.user_id
    order by (p.status in ('active', 'grace', 'billing_retry')) desc, p.expires_at desc nulls last
    limit 1
  ) p on true;
end;
$$;

revoke execute on function
  librelog.record_purchase(text, text, uuid, text, text, timestamptz, text, boolean, text, timestamptz, text),
  librelog.sync_status()
from public, anon, authenticated;

grant execute on function librelog.sync_status() to authenticated;
grant execute on function
  librelog.record_purchase(text, text, uuid, text, text, timestamptz, text, boolean, text, timestamptz, text)
to service_role;
