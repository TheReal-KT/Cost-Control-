-- Snapshot of the seven existing Cost Control tables; no application records are copied.
-- Existing tables and identifiers are retained. New environments use this same migration.

create table if not exists public.users (
    user_id bigserial not null,
    first_name varchar(50) not null,
    last_name varchar(50) not null,
    email varchar(50) not null,
    password varchar(50) not null,
    user_type varchar(50) not null,
    constraint users_pkey PRIMARY KEY (user_id)
);

create table if not exists public.providers (
    provider_id bigserial not null,
    provider_name varchar(50) not null,
    provider_website varchar(50) not null,
    category varchar(50) not null,
    user_id bigint not null,
    constraint fk_providers_user FOREIGN KEY (user_id) REFERENCES users(user_id),
    constraint providers_pkey PRIMARY KEY (provider_id)
);

create table if not exists public.subscriptions (
    subscription_id bigserial not null,
    user_id bigint not null,
    provider_id bigint not null,
    subscription_name varchar(50) not null,
    subscription_price numeric(12,2) not null,
    billing_cycle varchar(50) not null,
    start_date date not null,
    renewal_date date not null,
    status varchar(50) not null,
    usage_level varchar(20),
    auto_renew boolean not null,
    created_at timestamp without time zone default CURRENT_TIMESTAMP,
    updated_at timestamp without time zone default CURRENT_TIMESTAMP,
    constraint ck_subscription_price_positive CHECK ((subscription_price >= (0)::numeric)),
    constraint subscriptions_pkey PRIMARY KEY (subscription_id),
    constraint subscriptions_provider_id_fkey FOREIGN KEY (provider_id) REFERENCES providers(provider_id),
    constraint subscriptions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id)
);

create table if not exists public.budgets (
    budget_id bigserial not null,
    user_id bigint not null,
    budget_limit numeric(12,2) not null,
    currency varchar(3) not null,
    month integer not null,
    year integer not null,
    constraint budgets_pkey PRIMARY KEY (budget_id),
    constraint budgets_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id),
    constraint ck_budget_limit_non_negative CHECK ((budget_limit >= (0)::numeric)),
    constraint ck_budget_month CHECK (((month >= 1) AND (month <= 12))),
    constraint uq_budget_user_month_year UNIQUE (user_id, month, year)
);

create table if not exists public.recommendations (
    recommendation_id bigserial not null,
    user_id bigint not null,
    subscription_id bigint not null,
    recommendation_type varchar(50) not null,
    reason varchar(1000) not null,
    confidence_score numeric(5,4) not null,
    evidence jsonb,
    model_name varchar(100),
    model_version varchar(50),
    expires_at timestamp with time zone,
    created_at timestamp without time zone default CURRENT_TIMESTAMP,
    title varchar(150) not null,
    constraint ck_recommendation_confidence CHECK (((confidence_score >= (0)::numeric) AND (confidence_score <= (1)::numeric))),
    constraint recommendations_pkey PRIMARY KEY (recommendation_id),
    constraint recommendations_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id),
    constraint recommendations_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id)
);

create table if not exists public.notifications (
    notification_id bigserial not null,
    user_id bigint not null,
    subscription_id bigint not null,
    notification_type varchar(50) not null,
    message varchar(1000) not null,
    notification_date timestamp with time zone not null default now(),
    status varchar(20) not null,
    title varchar(150) not null,
    constraint notifications_pkey PRIMARY KEY (notification_id),
    constraint notifications_subscription_id_fkey FOREIGN KEY (subscription_id) REFERENCES subscriptions(subscription_id),
    constraint notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id)
);

create table if not exists public.user_decision (
    decision_id bigserial not null,
    user_id bigint not null,
    recommendation_id bigint not null,
    decision varchar(20) not null,
    decision_date timestamp with time zone not null default now(),
    title varchar(150) not null,
    constraint uq_user_decision_recommendation UNIQUE (recommendation_id),
    constraint user_decision_pkey PRIMARY KEY (decision_id),
    constraint user_decision_recommendation_id_fkey FOREIGN KEY (recommendation_id) REFERENCES recommendations(recommendation_id),
    constraint user_decision_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(user_id)
);

create index if not exists ix_providers_user_id ON public.providers USING btree (user_id);
create unique index if not exists ix_users_email ON public.users USING btree (email);
create index if not exists ix_subscription_user_status_renewal ON public.subscriptions USING btree (user_id, status, renewal_date);
create index if not exists ix_subscription_user_provider_status ON public.subscriptions USING btree (user_id, provider_id, status);
create index if not exists ix_notification_subscription ON public.notifications USING btree (subscription_id);
create index if not exists ix_notification_user_date ON public.notifications USING btree (user_id, notification_date);
create index if not exists ix_notification_user_status ON public.notifications USING btree (user_id, status);
create index if not exists ix_user_decision_date ON public.user_decision USING btree (decision_date);
create index if not exists ix_user_decision_user ON public.user_decision USING btree (user_id);
create index if not exists ix_recommendation_subscription ON public.recommendations USING btree (subscription_id);
create index if not exists ix_recommendation_created_at ON public.recommendations USING btree (created_at);
create index if not exists ix_budget_user ON public.budgets USING btree (user_id);

-- Complete the Deliverable 4 MVP while retaining the legacy bigint identifiers.
-- Supabase Auth owns credentials; an unlinked legacy profile is deliberately inaccessible.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

alter table public.users
    add column auth_user_id uuid unique default auth.uid() references auth.users(id) on delete set null,
    add column created_at timestamptz not null default now(),
    add column updated_at timestamptz not null default now(),
    alter column email type varchar(254),
    alter column email set default (auth.jwt() ->> 'email'),
    alter column password drop not null,
    alter column user_type set default 'individual';
comment on column public.users.password is
    'Legacy data only. Denied to API roles. Never write new credentials here; use Supabase Auth.';
comment on column public.users.auth_user_id is
    'Trusted link to Supabase Auth. Existing profiles require explicit administrator reconciliation.';

alter table public.providers
    alter column provider_website type varchar(2048),
    alter column provider_website drop not null,
    add constraint providers_owner_key unique (user_id, provider_id);

alter table public.subscriptions
    alter column subscription_name type varchar(150),
    alter column provider_id drop not null,
    alter column start_date set default current_date,
    alter column status set default 'active',
    alter column auto_renew set default true,
    alter column created_at type timestamptz using created_at at time zone 'UTC',
    alter column updated_at type timestamptz using updated_at at time zone 'UTC',
    alter column created_at set not null,
    alter column updated_at set not null,
    add column currency varchar(3) not null default 'ZAR',
    add column category varchar(50) not null default 'other',
    add column plan_name varchar(100),
    add column importance varchar(20) not null default 'medium',
    add constraint subscriptions_owner_key unique (user_id, subscription_id),
    add constraint subscriptions_provider_owner_fkey
        foreign key (user_id, provider_id) references public.providers(user_id, provider_id),
    add constraint subscriptions_billing_cycle_check check (billing_cycle in ('monthly', 'annual')),
    add constraint subscriptions_status_check check (status in ('active', 'paused', 'cancelled')),
    add constraint subscriptions_usage_check check (usage_level in ('unknown', 'low', 'medium', 'high')),
    add constraint subscriptions_importance_check check (importance in ('low', 'medium', 'high')),
    add constraint subscriptions_dates_check check (renewal_date >= start_date),
    add constraint subscriptions_currency_check check (currency ~ '^[A-Z]{3}$'),
    add constraint subscriptions_price_finite_check check (subscription_price < 'Infinity'::numeric),
    add constraint subscriptions_name_check check (length(btrim(subscription_name)) > 0),
    add constraint subscriptions_category_check check (length(btrim(category)) > 0);
alter table public.subscriptions drop constraint subscriptions_provider_id_fkey;
create index subscriptions_provider_id_idx on public.subscriptions(provider_id);

alter table public.budgets
    add constraint budgets_currency_check check (currency ~ '^[A-Z]{3}$'),
    add constraint budgets_year_check check (year between 2000 and 9999),
    add constraint budgets_limit_finite_check check (budget_limit < 'Infinity'::numeric);

alter table public.recommendations
    alter column created_at type timestamptz using created_at at time zone 'UTC',
    alter column created_at set not null,
    alter column evidence set default '{}'::jsonb,
    add column potential_monthly_saving numeric(12,2) not null default 0,
    add column potential_annual_saving numeric(12,2) not null default 0,
    add constraint recommendations_owner_key unique (user_id, recommendation_id),
    add constraint recommendations_subscription_owner_fkey
        foreign key (user_id, subscription_id)
        references public.subscriptions(user_id, subscription_id) on delete cascade,
    add constraint recommendations_action_check
        check (recommendation_type in ('keep', 'review', 'cancel', 'downgrade', 'pause')),
    add constraint recommendations_savings_check
        check (potential_monthly_saving >= 0 and potential_monthly_saving < 'Infinity'::numeric
           and potential_annual_saving >= 0 and potential_annual_saving < 'Infinity'::numeric),
    add constraint recommendations_evidence_check check (jsonb_typeof(evidence) = 'object');
alter table public.recommendations drop constraint recommendations_subscription_id_fkey;
create index recommendations_user_subscription_idx on public.recommendations(user_id, subscription_id);

alter table public.notifications
    add column read_at timestamptz,
    add constraint notifications_subscription_owner_fkey
        foreign key (user_id, subscription_id)
        references public.subscriptions(user_id, subscription_id) on delete cascade;
alter table public.notifications drop constraint notifications_subscription_id_fkey;
create index notifications_user_subscription_idx on public.notifications(user_id, subscription_id);

alter table public.user_decision
    add constraint user_decision_recommendation_owner_fkey
        foreign key (user_id, recommendation_id)
        references public.recommendations(user_id, recommendation_id) on delete cascade,
    add constraint user_decision_value_check check (decision in ('approved', 'rejected', 'ignored'));
alter table public.user_decision drop constraint user_decision_recommendation_id_fkey;
create index user_decision_user_recommendation_idx on public.user_decision(user_id, recommendation_id);

create table public.user_settings (
    user_id bigint primary key references public.users(user_id),
    currency varchar(3) not null default 'ZAR' check (currency ~ '^[A-Z]{3}$'),
    time_zone varchar(100) not null default 'Africa/Johannesburg',
    reminder_days_before integer not null default 7 check (reminder_days_before between 0 and 365),
    notifications_enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table public.reminders (
    reminder_id bigint generated always as identity primary key,
    user_id bigint not null references public.users(user_id),
    subscription_id bigint not null,
    title varchar(150) not null check (length(btrim(title)) > 0),
    message varchar(1000),
    remind_at timestamptz not null,
    status varchar(20) not null default 'pending'
        check (status in ('pending', 'completed', 'dismissed')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint reminders_subscription_owner_fkey
        foreign key (user_id, subscription_id)
        references public.subscriptions(user_id, subscription_id) on delete cascade
);
create index reminders_user_time_idx on public.reminders(user_id, remind_at);
create index reminders_subscription_idx on public.reminders(subscription_id);
create index reminders_user_subscription_idx on public.reminders(user_id, subscription_id);
create index reminders_pending_idx on public.reminders(remind_at) where status = 'pending';

create table public.activity_logs (
    activity_id bigint generated always as identity primary key,
    user_id bigint not null references public.users(user_id),
    entity_type varchar(30) not null
        check (entity_type in ('subscriptions', 'reminders', 'budgets', 'user_decision')),
    entity_id bigint not null,
    event_type varchar(10) not null check (event_type in ('created', 'updated', 'deleted')),
    occurred_at timestamptz not null default now()
);
create index activity_logs_user_time_idx on public.activity_logs(user_id, occurred_at desc);
comment on table public.activity_logs is
    'Database-produced immutable activity history; no secrets, full row snapshots or client-written evidence.';
comment on table public.reminders is
    'User-managed renewal schedules. Notifications separately represent backend-delivered messages.';

create function private.set_updated_at()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;
revoke all on function private.set_updated_at() from public, anon, authenticated;

create function private.validate_time_zone()
returns trigger language plpgsql security invoker set search_path = ''
as $$
begin
    if not exists (select 1 from pg_catalog.pg_timezone_names where name = new.time_zone) then
        raise exception 'Invalid IANA time zone' using errcode = '23514';
    end if;
    return new;
end;
$$;
revoke all on function private.validate_time_zone() from public, anon, authenticated;
create trigger user_settings_time_zone
before insert or update on public.user_settings
for each row execute function private.validate_time_zone();

-- Only a trigger can call this privileged writer; API roles cannot fabricate activity.
-- For signed-in calls, verify ownership again before bypassing the activity table's RLS.
create function private.record_activity()
returns trigger language plpgsql security definer set search_path = ''
as $$
declare
    changed_row jsonb;
    owner_id bigint;
    entity_key text;
begin
    if tg_op = 'DELETE' then
        changed_row := to_jsonb(old);
    else
        changed_row := to_jsonb(new);
    end if;
    owner_id := (changed_row ->> 'user_id')::bigint;
    if auth.uid() is not null and not exists (
        select 1 from public.users
        where user_id = owner_id and auth_user_id = auth.uid()
    ) then
        raise exception 'Activity owner does not match authenticated user' using errcode = '42501';
    end if;
    entity_key := case tg_table_name
        when 'subscriptions' then 'subscription_id'
        when 'reminders' then 'reminder_id'
        when 'budgets' then 'budget_id'
        when 'user_decision' then 'decision_id'
        else null
    end;
    if entity_key is null then
        raise exception 'Unsupported activity source';
    end if;
    insert into public.activity_logs(user_id, entity_type, entity_id, event_type)
    values (
        owner_id, tg_table_name, (changed_row ->> entity_key)::bigint,
        case tg_op when 'INSERT' then 'created' when 'UPDATE' then 'updated' else 'deleted' end
    );
    if tg_op = 'DELETE' then return old; end if;
    return new;
end;
$$;
revoke all on function private.record_activity() from public, anon, authenticated;

create trigger users_updated_at before update on public.users
for each row execute function private.set_updated_at();

create trigger subscriptions_updated_at before update on public.subscriptions
for each row execute function private.set_updated_at();

create trigger user_settings_updated_at before update on public.user_settings
for each row execute function private.set_updated_at();

create trigger reminders_updated_at before update on public.reminders
for each row execute function private.set_updated_at();

create trigger subscriptions_activity after insert or update or delete on public.subscriptions
for each row execute function private.record_activity();

create trigger reminders_activity after insert or update or delete on public.reminders
for each row execute function private.record_activity();

create trigger budgets_activity after insert or update or delete on public.budgets
for each row execute function private.record_activity();

create trigger user_decision_activity after insert or update or delete on public.user_decision
for each row execute function private.record_activity();

-- Remove broad default grants, including TRUNCATE (which RLS does not protect).
alter table public.users enable row level security;
revoke all on public.users from public, anon, authenticated;
alter table public.providers enable row level security;
revoke all on public.providers from public, anon, authenticated;
alter table public.subscriptions enable row level security;
revoke all on public.subscriptions from public, anon, authenticated;
alter table public.budgets enable row level security;
revoke all on public.budgets from public, anon, authenticated;
alter table public.recommendations enable row level security;
revoke all on public.recommendations from public, anon, authenticated;
alter table public.notifications enable row level security;
revoke all on public.notifications from public, anon, authenticated;
alter table public.user_decision enable row level security;
revoke all on public.user_decision from public, anon, authenticated;
alter table public.user_settings enable row level security;
revoke all on public.user_settings from public, anon, authenticated;
alter table public.reminders enable row level security;
revoke all on public.reminders from public, anon, authenticated;
alter table public.activity_logs enable row level security;
revoke all on public.activity_logs from public, anon, authenticated;

grant usage on schema public to authenticated;
grant select (user_id, auth_user_id, first_name, last_name, email, user_type, created_at, updated_at)
    on public.users to authenticated;
grant insert (auth_user_id, first_name, last_name) on public.users to authenticated;
grant update (first_name, last_name) on public.users to authenticated;
create policy users_select_own on public.users for select to authenticated
    using (auth_user_id = (select auth.uid()));
create policy users_insert_own on public.users for insert to authenticated
    with check (auth_user_id = (select auth.uid()) and password is null and user_type = 'individual');
create policy users_update_own on public.users for update to authenticated
    using (auth_user_id = (select auth.uid())) with check (auth_user_id = (select auth.uid()));

grant select, delete on public.providers to authenticated;
grant insert (user_id, provider_name, provider_website, category) on public.providers to authenticated;
grant update (provider_name, provider_website, category) on public.providers to authenticated;
create policy providers_select_own on public.providers for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy providers_insert_own on public.providers for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy providers_update_own on public.providers for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy providers_delete_own on public.providers for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select, delete on public.subscriptions to authenticated;
grant insert (user_id, provider_id, subscription_name, subscription_price, billing_cycle, start_date, renewal_date, status, usage_level, auto_renew, currency, category, plan_name, importance) on public.subscriptions to authenticated;
grant update (provider_id, subscription_name, subscription_price, billing_cycle, start_date, renewal_date, status, usage_level, auto_renew, currency, category, plan_name, importance) on public.subscriptions to authenticated;
create policy subscriptions_select_own on public.subscriptions for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy subscriptions_insert_own on public.subscriptions for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy subscriptions_update_own on public.subscriptions for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy subscriptions_delete_own on public.subscriptions for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select, delete on public.budgets to authenticated;
grant insert (user_id, budget_limit, currency, month, year) on public.budgets to authenticated;
grant update (budget_limit, currency, month, year) on public.budgets to authenticated;
create policy budgets_select_own on public.budgets for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy budgets_insert_own on public.budgets for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy budgets_update_own on public.budgets for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy budgets_delete_own on public.budgets for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select, delete on public.user_settings to authenticated;
grant insert (user_id, currency, time_zone, reminder_days_before, notifications_enabled) on public.user_settings to authenticated;
grant update (currency, time_zone, reminder_days_before, notifications_enabled) on public.user_settings to authenticated;
create policy user_settings_select_own on public.user_settings for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_settings_insert_own on public.user_settings for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_settings_update_own on public.user_settings for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_settings_delete_own on public.user_settings for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select, delete on public.reminders to authenticated;
grant insert (user_id, subscription_id, title, message, remind_at, status) on public.reminders to authenticated;
grant update (subscription_id, title, message, remind_at, status) on public.reminders to authenticated;
create policy reminders_select_own on public.reminders for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy reminders_insert_own on public.reminders for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy reminders_update_own on public.reminders for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy reminders_delete_own on public.reminders for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select, delete on public.user_decision to authenticated;
grant insert (user_id, recommendation_id, decision, title) on public.user_decision to authenticated;
grant update (decision, title) on public.user_decision to authenticated;
create policy user_decision_select_own on public.user_decision for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_decision_insert_own on public.user_decision for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_decision_update_own on public.user_decision for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy user_decision_delete_own on public.user_decision for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select on public.recommendations to authenticated;
create policy recommendations_select_own on public.recommendations for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select on public.notifications to authenticated;
create policy notifications_select_own on public.notifications for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant select on public.activity_logs to authenticated;
create policy activity_logs_select_own on public.activity_logs for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

grant update (read_at) on public.notifications to authenticated;
create policy notifications_update_own on public.notifications for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid()))) with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

-- Sequence usage is needed only for client-created legacy BIGSERIAL rows.
revoke all on sequence public.users_user_id_seq from public, anon, authenticated;
grant usage on sequence public.users_user_id_seq to authenticated;
revoke all on sequence public.providers_provider_id_seq from public, anon, authenticated;
grant usage on sequence public.providers_provider_id_seq to authenticated;
revoke all on sequence public.subscriptions_subscription_id_seq from public, anon, authenticated;
grant usage on sequence public.subscriptions_subscription_id_seq to authenticated;
revoke all on sequence public.budgets_budget_id_seq from public, anon, authenticated;
grant usage on sequence public.budgets_budget_id_seq to authenticated;
revoke all on sequence public.recommendations_recommendation_id_seq from public, anon, authenticated;
revoke all on sequence public.notifications_notification_id_seq from public, anon, authenticated;
revoke all on sequence public.user_decision_decision_id_seq from public, anon, authenticated;
grant usage on sequence public.user_decision_decision_id_seq to authenticated;
revoke all on sequence public.reminders_reminder_id_seq from public, anon, authenticated;
grant usage on sequence public.reminders_reminder_id_seq to authenticated;
revoke all on sequence public.activity_logs_activity_id_seq from public, anon, authenticated;

-- Explicit backend privileges for trusted jobs and recommendation writers.
grant usage on schema public to service_role;
grant all on public.users to service_role;
grant all on public.providers to service_role;
grant all on public.subscriptions to service_role;
grant all on public.budgets to service_role;
grant all on public.recommendations to service_role;
grant all on public.notifications to service_role;
grant all on public.user_decision to service_role;
grant all on public.user_settings to service_role;
grant all on public.reminders to service_role;
grant all on public.activity_logs to service_role;
grant all on sequence public.users_user_id_seq to service_role;
grant all on sequence public.providers_provider_id_seq to service_role;
grant all on sequence public.subscriptions_subscription_id_seq to service_role;
grant all on sequence public.budgets_budget_id_seq to service_role;
grant all on sequence public.recommendations_recommendation_id_seq to service_role;
grant all on sequence public.notifications_notification_id_seq to service_role;
grant all on sequence public.user_decision_decision_id_seq to service_role;
grant all on sequence public.reminders_reminder_id_seq to service_role;
grant all on sequence public.activity_logs_activity_id_seq to service_role;
