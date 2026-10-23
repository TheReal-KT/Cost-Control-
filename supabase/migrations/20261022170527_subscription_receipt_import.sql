-- Receipt IDs survive service deletion, so retrying an old scan cannot resurrect it.
-- No email bodies, subjects or OAuth credentials are stored here.
create table public.subscription_receipts (
    user_id bigint not null references public.users(user_id) on delete cascade,
    source_id varchar(256) not null check (char_length(btrim(source_id)) between 1 and 256),
    source_url varchar(2048),
    imported_at timestamptz not null default now(),
    primary key (user_id, source_id),
    constraint subscription_receipts_url_check check (
        source_url is null or source_url ~ '^https://mail\.google\.com/mail/'
    )
);

alter table public.subscription_receipts enable row level security;
revoke all on public.subscription_receipts from public, anon, authenticated;
grant select on public.subscription_receipts to authenticated;
grant insert (user_id, source_id, source_url) on public.subscription_receipts to authenticated;
create policy subscription_receipts_select_own on public.subscription_receipts
    for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy subscription_receipts_insert_own on public.subscription_receipts
    for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

create function public.import_subscription_receipts(receipts jsonb)
returns jsonb
language plpgsql security invoker set search_path = ''
as $import$
declare
    owner_id bigint;
    receipt jsonb;
    receipt_source text;
    receipt_name text;
    receipt_currency text;
    receipt_cycle text;
    receipt_price numeric(12,2);
    receipt_renewal date;
    receipt_url text;
    imported_count integer := 0;
    skipped_count integer := 0;
    inserted_count integer;
begin
    if auth.uid() is null then
        raise exception 'An authenticated user is required' using errcode = '42501';
    end if;
    select app_user.user_id into owner_id from public.users as app_user
        where app_user.auth_user_id = auth.uid();
    if owner_id is null then
        raise exception 'The authenticated user profile is required' using errcode = '42501';
    end if;
    if receipts is null or jsonb_typeof(receipts) <> 'array' then
        raise exception 'Receipts must be an array' using errcode = '23514';
    end if;
    if jsonb_array_length(receipts) > 100 then
        raise exception 'At most 100 receipts may be imported' using errcode = '23514';
    end if;

    -- Serialize imports for this owner, including different receipts for the same service.
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('subscription-import:' || owner_id::text, 0)
    );
    for receipt in select value from jsonb_array_elements(receipts) loop
        if jsonb_typeof(receipt) <> 'object'
           or jsonb_typeof(receipt->'source_id') is distinct from 'string'
           or jsonb_typeof(receipt->'service_name') is distinct from 'string'
           or jsonb_typeof(receipt->'price') is distinct from 'string'
           or jsonb_typeof(receipt->'currency') is distinct from 'string'
           or jsonb_typeof(receipt->'billing_cycle') is distinct from 'string'
           or jsonb_typeof(receipt->'renewal_date') is distinct from 'string'
           or (receipt ? 'source_url' and jsonb_typeof(receipt->'source_url') not in ('string','null'))
           or receipt - array['source_id','service_name','price','currency','billing_cycle',
                              'renewal_date','source_url']::text[] <> '{}'::jsonb then
            raise exception 'Invalid receipt fields' using errcode = '23514';
        end if;
        receipt_source := btrim(receipt->>'source_id');
        receipt_name := btrim(receipt->>'service_name');
        receipt_currency := receipt->>'currency';
        receipt_cycle := case receipt->>'billing_cycle'
            when 'MONTHLY' then 'monthly' when 'YEARLY' then 'annual' else null end;
        receipt_url := receipt->>'source_url';
        if char_length(receipt_source) not between 1 and 256
           or char_length(receipt_name) not between 1 and 150
           or receipt_currency !~ '^[A-Z]{3}$' or receipt_cycle is null
           or (receipt->>'price') !~ '^[0-9]{1,10}(\.[0-9]{1,2})?$'
           or (receipt->>'renewal_date') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
           or (receipt_url is not null and (char_length(receipt_url) > 2048
               or receipt_url !~ '^https://mail\.google\.com/mail/')) then
            raise exception 'Invalid receipt values' using errcode = '23514';
        end if;
        begin
            receipt_price := (receipt->>'price')::numeric;
            receipt_renewal := (receipt->>'renewal_date')::date;
        exception when datetime_field_overflow or invalid_datetime_format or numeric_value_out_of_range then
            raise exception 'Invalid receipt amount or date' using errcode = '23514';
        end;
        if receipt_renewal < current_date or receipt_renewal > current_date + 400 then
            raise exception 'Renewal must be current and within 400 days' using errcode = '23514';
        end if;

        insert into public.subscription_receipts(user_id, source_id, source_url)
            values(owner_id, receipt_source, receipt_url)
            on conflict (user_id, source_id) do nothing;
        get diagnostics inserted_count = row_count;
        if inserted_count = 0 then
            skipped_count := skipped_count + 1;
            continue;
        end if;
        -- Preserve manual edits, paused/cancelled services and previously imported records.
        if exists(select 1 from public.subscriptions as saved
                  where saved.user_id = owner_id
                    and lower(btrim(saved.subscription_name)) = lower(receipt_name)
                    and saved.currency = receipt_currency and saved.billing_cycle = receipt_cycle) then
            skipped_count := skipped_count + 1;
            continue;
        end if;
        insert into public.subscriptions(user_id, subscription_name, subscription_price, currency,
                                        billing_cycle, renewal_date, usage_level, auto_renew)
            values(owner_id, receipt_name, receipt_price, receipt_currency,
                   receipt_cycle, receipt_renewal, 'unknown', true);
        imported_count := imported_count + 1;
    end loop;
    return jsonb_build_object('imported', imported_count, 'skipped', skipped_count);
end;
$import$;

revoke all on function public.import_subscription_receipts(jsonb) from public, anon, authenticated;
grant execute on function public.import_subscription_receipts(jsonb) to authenticated;

comment on function public.import_subscription_receipts(jsonb) is
    'Atomic owner-scoped discovery import. Existing services are never overwritten; retries use receipt IDs.';
