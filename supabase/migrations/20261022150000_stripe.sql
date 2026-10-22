-- Web checkout through Stripe (SPEC.md §15.4–15.5). docs/billing.md has the
-- Stripe setup.
--
-- A web purchase is a one-off payment for 1 or 3 years, not a subscription,
-- so it can't carry its own expiry the way a store renewal does: it buys
-- time from the later of now and the date the account is already paid
-- through, so buying early never wastes what's left. The stripe-webhook Edge
-- Function calls record_stripe_purchase() with the service role; refunds and
-- disputes go through record_purchase()'s refund path as the stores' do.

-- When a purchase's time began. Only web purchases stack, so only they set
-- it; a refund uses it to move the purchases stacked after it back.
alter table librelog.purchases add column starts_at timestamptz;

-- Records a paid Checkout Session (p_status 'active') or takes one back
-- ('refunded', 'revoked' for a dispute), and returns the account's
-- sync_until afterwards.
--
-- p_original_id is the payment's PaymentIntent id (the Checkout Session id
-- if there was nothing to pay). Recording the same payment again, under any
-- event id, changes nothing; so does p_event_id again (Stripe's event id).
-- Errors as record_purchase: 404 no such account (or, for a refund, no such
-- purchase), 409 the payment is on file for another account.
create function librelog.record_stripe_purchase(
  p_original_id text,
  p_user        uuid,
  p_product_id  text,
  p_environment text,
  p_years       integer,
  p_status      text,
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
  v_start timestamptz;
  v_unused interval;
begin
  if p_original_id is null or p_original_id = '' then
    raise exception 'missing purchase id' using errcode = 'PT400';
  end if;
  if p_status is null or p_status not in ('active', 'refunded', 'revoked') then
    raise exception 'status must be active, refunded or revoked' using errcode = 'PT400';
  end if;

  -- Locks in record_purchase's order: the purchase, then the account.
  select * into v_existing
  from librelog.purchases
  where store = 'stripe' and original_id = p_original_id
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
  select sync_until into v_until from librelog.accounts where user_id = v_user for update;

  if p_event_id is not null then
    insert into librelog.billing_events (store, event_id)
    values ('stripe', p_event_id)
    on conflict do nothing;
    if not found then
      return v_until; -- already applied
    end if;
  end if;

  if p_status = 'active' then
    -- Paid once, granted once: checkout.session.completed and
    -- async_payment_succeeded can both arrive for one payment, and a
    -- refunded payment isn't paid again.
    if v_existing.original_id is not null then
      return v_until;
    end if;
    if p_years is null or p_years < 1 or p_years > 10 then
      raise exception 'years must be 1 to 10' using errcode = 'PT400';
    end if;

    v_start := greatest(now(), v_until); -- greatest() skips a null sync_until
    v_until := librelog.record_purchase(
      'stripe', p_original_id, v_user, p_product_id, p_environment,
      v_start + make_interval(years => p_years), 'active', false, p_event, p_event_at);
    update librelog.purchases
    set starts_at = v_start
    where store = 'stripe' and original_id = p_original_id;
    return v_until;
  end if;

  -- A refund or a dispute.
  if v_existing.original_id is null then
    raise exception 'no such purchase' using errcode = 'PT404';
  end if;
  if v_existing.status in ('refunded', 'revoked') then
    return v_until; -- a dispute after a refund, or the other way round
  end if;
  if v_existing.last_event_at is not null and p_event_at < v_existing.last_event_at then
    return v_until;
  end if;

  -- Web purchases stacked after this one move back by the time it still had
  -- to run, so taking it back takes back exactly the time it bought.
  v_unused := greatest(v_existing.expires_at - greatest(v_existing.starts_at, now()), interval '0');
  if v_unused > interval '0' then
    update librelog.purchases
    set starts_at  = starts_at - v_unused,
        expires_at = expires_at - v_unused,
        updated_at = now()
    where user_id = v_user
      and store = 'stripe'
      and original_id <> p_original_id
      and status = 'active'
      and starts_at >= v_existing.expires_at;
  end if;

  -- Then record_purchase takes this one back and falls back to what the
  -- account's other purchases and any hand grant still cover.
  return librelog.record_purchase(
    'stripe', p_original_id, v_user, v_existing.product_id, v_existing.environment,
    v_existing.expires_at, p_status, false, p_event, p_event_at);
end;
$$;

revoke execute on function
  librelog.record_stripe_purchase(text, uuid, text, text, integer, text, text, timestamptz, text)
from public, anon, authenticated;

grant execute on function
  librelog.record_stripe_purchase(text, uuid, text, text, integer, text, text, timestamptz, text)
to service_role;
