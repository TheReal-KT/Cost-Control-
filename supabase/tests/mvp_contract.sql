-- Privileged runner required. Fixtures and JWT settings are transaction-local.
-- Sequence values may advance even when the fixture data is rolled back.
begin;
do $contract$
declare
    auth_a uuid := gen_random_uuid();
    auth_b uuid := gen_random_uuid();
    email_a text;
    email_b text;
    user_a bigint;
    user_b bigint;
    provider_a bigint;
    provider_b bigint;
    sub_a bigint;
    sub_b bigint;
    reminder_a bigint;
    rec_a bigint;
    rec_b bigint;
    attempt text;
    app_table text;
    changed_count integer;
    assertion_count integer := 0;
begin
    email_a := auth_a::text || '@example.invalid';
    email_b := auth_b::text || '@example.invalid';
    insert into auth.users(id,aud,role,email)
        values(auth_a,'authenticated','authenticated',email_a),
              (auth_b,'authenticated','authenticated',email_b);

    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',
        jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha')
        returning user_id into user_a;
    if not exists(select 1 from public.users where user_id=user_a and auth_user_id=auth_a and email=email_a) then
        raise exception 'Profile defaults must use the signed identity and email';
    end if;
    assertion_count := assertion_count + 1;
    execute 'reset role';

    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',
        jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Beta')
        returning user_id into user_b;
    if exists(select 1 from public.users where user_id=user_a) then
        raise exception 'Second user must not read the first profile';
    end if;
    assertion_count := assertion_count + 1;
    execute 'reset role';
    perform set_config('request.jwt.claim.sub','',true);
    perform set_config('request.jwt.claims','{}',true);

    -- Trusted fixture creation also exercises the backend write path.
    insert into public.providers(user_id,provider_name,category)
        values(user_a,'Alpha provider','productivity') returning provider_id into provider_a;
    insert into public.providers(user_id,provider_name,category)
        values(user_b,'Beta provider','productivity') returning provider_id into provider_b;
    insert into public.subscriptions(user_id,provider_id,subscription_name,subscription_price,billing_cycle,renewal_date)
        values(user_a,provider_a,'Alpha monthly',120,'monthly',current_date+30)
        returning subscription_id into sub_a;
    insert into public.subscriptions(user_id,provider_id,subscription_name,subscription_price,billing_cycle,renewal_date)
        values(user_b,provider_b,'Beta annual',1200,'annual',current_date+365)
        returning subscription_id into sub_b;
    insert into public.reminders(user_id,subscription_id,title,remind_at)
        values(user_a,sub_a,'Alpha renewal',now()+interval '7 days')
        returning reminder_id into reminder_a;
    insert into public.reminders(user_id,subscription_id,title,remind_at)
        values(user_b,sub_b,'Beta renewal',now()+interval '8 days');
    insert into public.budgets(user_id,budget_limit,currency,month,year)
        values(user_a,500,'ZAR',10,2026),(user_b,500,'ZAR',10,2026);
    insert into public.user_settings(user_id) values(user_a),(user_b);
    insert into public.recommendations(user_id,subscription_id,recommendation_type,reason,confidence_score,title)
        values(user_a,sub_a,'review','Reported low usage',0.8,'Review Alpha')
        returning recommendation_id into rec_a;
    insert into public.recommendations(user_id,subscription_id,recommendation_type,reason,confidence_score,title)
        values(user_b,sub_b,'keep','Reported high usage',0.8,'Keep Beta')
        returning recommendation_id into rec_b;
    insert into public.notifications(user_id,subscription_id,notification_type,message,status,title)
        values(user_a,sub_a,'renewal','Alpha renewal soon','delivered','Alpha'),
              (user_b,sub_b,'renewal','Beta renewal soon','delivered','Beta');

    -- Composite ownership must hold even for writers that bypass RLS.
    foreach attempt in array array[
        format('insert into public.subscriptions(user_id,provider_id,subscription_name,subscription_price,billing_cycle,renewal_date) values(%s,%s,''Cross owner'',10,''monthly'',current_date+30)',user_a,provider_b),
        format('insert into public.reminders(user_id,subscription_id,title,remind_at) values(%s,%s,''Cross owner'',now())',user_a,sub_b),
        format('insert into public.recommendations(user_id,subscription_id,recommendation_type,reason,confidence_score,title) values(%s,%s,''review'',''Cross owner'',0.5,''Cross owner'')',user_a,sub_b),
        format('insert into public.notifications(user_id,subscription_id,notification_type,message,status,title) values(%s,%s,''renewal'',''Cross owner'',''pending'',''Cross owner'')',user_a,sub_b),
        format('insert into public.user_decision(user_id,recommendation_id,decision,title) values(%s,%s,''approved'',''Cross owner'')',user_a,rec_b)
    ] loop
        begin
            execute attempt;
            raise exception 'Cross-owner reference was accepted: %',attempt;
        exception when foreign_key_violation then
            assertion_count := assertion_count + 1;
        end;
    end loop;

    insert into public.user_decision(user_id,recommendation_id,decision,title)
        values(user_a,rec_a,'approved','Review approved'),(user_b,rec_b,'ignored','No action');

    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',
        jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';

    foreach app_table in array array[
        'providers','subscriptions','budgets','reminders','user_settings',
        'recommendations','notifications','user_decision','activity_logs'
    ] loop
        execute format('select count(*) from public.%I where user_id=$1',app_table)
            into changed_count using user_b;
        if changed_count <> 0 then raise exception 'Other user visible in %',app_table; end if;
        execute format('select count(*) from public.%I where user_id=$1',app_table)
            into changed_count using user_a;
        if changed_count = 0 then raise exception 'Own rows missing in %',app_table; end if;
        assertion_count := assertion_count + 2;
    end loop;

    update public.subscriptions set subscription_price=999 where subscription_id=sub_b;
    get diagnostics changed_count = row_count;
    if changed_count <> 0 then raise exception 'Other user subscription updated'; end if;
    delete from public.reminders where user_id=user_b;
    get diagnostics changed_count = row_count;
    if changed_count <> 0 then raise exception 'Other user reminder deleted'; end if;
    assertion_count := assertion_count + 2;

    begin
        insert into public.budgets(user_id,budget_limit,currency,month,year)
            values(user_b,1,'ZAR',11,2026);
        raise exception 'Spoofed owner insert accepted';
    exception when insufficient_privilege then assertion_count := assertion_count + 1;
    end;

    -- Narrow column grants protect identity, credentials, generated history and backend output.
    foreach attempt in array array[
        'select password from public.users',
        'select * from public.users',
        format('insert into public.users(first_name,last_name,email) values(''Spoof'',''Email'',%L)',email_b),
        'insert into public.users(first_name,last_name,password) values(''Spoof'',''Password'',''never-store-this'')',
        'insert into public.users(first_name,last_name,user_type) values(''Spoof'',''Role'',''admin'')',
        format('update public.users set auth_user_id=%L where user_id=%s',auth_b,user_a),
        format('update public.users set user_type=''admin'' where user_id=%s',user_a),
        format('update public.subscriptions set user_id=%s where subscription_id=%s',user_b,sub_a),
        format('insert into public.recommendations(user_id,subscription_id,recommendation_type,reason,confidence_score,title) values(%s,%s,''cancel'',''Forged'',1,''Forged'')',user_a,sub_a),
        format('insert into public.activity_logs(user_id,entity_type,entity_id,event_type) values(%s,''subscriptions'',%s,''created'')',user_a,sub_a),
        format('update public.activity_logs set event_type=''deleted'' where user_id=%s',user_a),
        format('delete from public.activity_logs where user_id=%s',user_a),
        format('update public.notifications set status=''delivered'' where user_id=%s',user_a),
        'truncate table public.subscriptions',
        'select private.record_activity()'
    ] loop
        begin
            execute attempt;
            raise exception 'Forbidden operation accepted: %',attempt;
        exception when insufficient_privilege then assertion_count := assertion_count + 1;
        end;
    end loop;

    -- Domain boundaries reject invalid values without committing partial activity.
    foreach attempt in array array[
        format('update public.subscriptions set billing_cycle=''weekly'' where subscription_id=%s',sub_a),
        format('update public.subscriptions set subscription_price=-1 where subscription_id=%s',sub_a),
        format('update public.subscriptions set subscription_price=''NaN''::numeric where subscription_id=%s',sub_a),
        format('update public.subscriptions set renewal_date=start_date-1 where subscription_id=%s',sub_a),
        format('update public.subscriptions set importance=''invalid'' where subscription_id=%s',sub_a),
        format('update public.budgets set budget_limit=-1 where user_id=%s',user_a),
        format('update public.budgets set month=13 where user_id=%s',user_a),
        format('update public.user_settings set time_zone=''Invalid/Zone'' where user_id=%s',user_a),
        format('update public.user_settings set reminder_days_before=-1 where user_id=%s',user_a),
        format('update public.reminders set status=''invalid'' where reminder_id=%s',reminder_a),
        format('update public.user_decision set decision=''invalid'' where user_id=%s',user_a)
    ] loop
        begin
            execute attempt;
            raise exception 'Invalid value accepted: %',attempt;
        exception when check_violation then assertion_count := assertion_count + 1;
        end;
    end loop;

    begin
        insert into public.budgets(user_id,budget_limit,currency,month,year)
            values(user_a,600,'ZAR',10,2026);
        raise exception 'Duplicate monthly budget accepted';
    exception when unique_violation then assertion_count := assertion_count + 1;
    end;

    update public.subscriptions set subscription_price=130,usage_level='low' where subscription_id=sub_a;
    update public.reminders set remind_at=now()+interval '9 days',status='pending' where reminder_id=reminder_a;
    if not exists(select 1 from public.reminders where reminder_id=reminder_a and remind_at=now()+interval '9 days') then
        raise exception 'Reminder reschedule failed';
    end if;
    update public.user_decision set decision='rejected' where recommendation_id=rec_a;
    update public.notifications set read_at=now() where user_id=user_a;
    if not exists(select 1 from public.user_decision where recommendation_id=rec_a and decision='rejected')
       or not exists(select 1 from public.notifications where user_id=user_a and read_at is not null) then
        raise exception 'Allowed decision or notification update failed';
    end if;
    assertion_count := assertion_count + 2;

    delete from public.subscriptions where subscription_id=sub_a;
    foreach app_table in array array['subscriptions','reminders','recommendations','notifications','user_decision'] loop
        execute format('select count(*) from public.%I where user_id=$1',app_table)
            into changed_count using user_a;
        if changed_count <> 0 then raise exception 'Dependent rows retained in %',app_table; end if;
        assertion_count := assertion_count + 1;
    end loop;
    if not exists(select 1 from public.activity_logs
        where user_id=user_a and entity_type='subscriptions' and entity_id=sub_a and event_type='deleted') then
        raise exception 'Delete activity missing';
    end if;
    assertion_count := assertion_count + 1;

    execute 'reset role';
    if not exists(select 1 from public.subscriptions where subscription_id=sub_b) then
        raise exception 'Deleting Alpha affected Beta';
    end if;
    assertion_count := assertion_count + 1;
    perform set_config('request.jwt.claim.sub','',true);
    perform set_config('request.jwt.claims','{}',true);
    execute 'set local role anon';
    foreach app_table in array array[
        'users','providers','subscriptions','budgets','reminders','user_settings',
        'recommendations','notifications','user_decision','activity_logs'
    ] loop
        begin
            execute format('select user_id from public.%I',app_table);
            raise exception 'Anonymous access accepted in %',app_table;
        exception when insufficient_privilege then assertion_count := assertion_count + 1;
        end;
    end loop;
    execute 'reset role';
    raise notice 'Passed % database contract assertions; fixture transaction will roll back',assertion_count;
end;
$contract$;
rollback;
select 'MVP database contract assertions passed; fixture data rolled back' as result;
