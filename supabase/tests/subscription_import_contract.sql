-- Fixtures roll back; identity sequence counters may still advance.
begin;
do $contract$
declare
    auth_a uuid := gen_random_uuid();
    auth_b uuid := gen_random_uuid();
    email_a text := gen_random_uuid()::text || '@example.invalid';
    email_b text := gen_random_uuid()::text || '@example.invalid';
    user_a bigint;
    user_b bigint;
    imported_id bigint;
    result jsonb;
    receipt jsonb := jsonb_build_object(
        'source_id','receipt-1','service_name','Example Music','price','99.99',
        'currency','ZAR','billing_cycle','MONTHLY','renewal_date',(current_date+20)::text,
        'source_url','https://mail.google.com/mail/u/0/#all/receipt-1');
    invalid_receipt jsonb;
    total integer;
    amount numeric;
begin
    insert into auth.users(id,aud,role,email) values
        (auth_a,'authenticated','authenticated',email_a),
        (auth_b,'authenticated','authenticated',email_b);
    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a)::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Import','Alpha') returning user_id into user_a;
    result := public.import_subscription_receipts(jsonb_build_array(receipt));
    if result <> '{"imported":1,"skipped":0}'::jsonb then raise exception 'First import failed: %',result; end if;
    select subscription_id into imported_id from public.subscriptions where user_id=user_a;
    result := public.import_subscription_receipts(jsonb_build_array(receipt));
    if result <> '{"imported":0,"skipped":1}'::jsonb then raise exception 'Retry was not idempotent'; end if;

    -- Another invoice must not replace the user's later price/cancellation edits.
    update public.subscriptions set subscription_price=42,status='cancelled' where subscription_id=imported_id;
    result := public.import_subscription_receipts(jsonb_build_array(
        receipt || '{"source_id":"receipt-2","service_name":" example music ","price":"199"}'::jsonb));
    if result <> '{"imported":0,"skipped":1}'::jsonb then raise exception 'Existing service duplicated'; end if;
    select subscription_price into amount from public.subscriptions where subscription_id=imported_id and status='cancelled';
    if amount is distinct from 42 then raise exception 'Manual edits were overwritten'; end if;

    -- The same service billed in another currency is a distinct amount, never a conversion.
    result := public.import_subscription_receipts(jsonb_build_array(
        receipt || '{"source_id":"receipt-usd","currency":"USD"}'::jsonb));
    if result->>'imported' <> '1' then raise exception 'Separate currency not imported'; end if;

    -- Every invalid batch is atomic, including its earlier valid entries and receipt IDs.
    foreach invalid_receipt in array array[
        receipt || '{"price":"NaN"}'::jsonb,
        receipt || '{"price":"1.999"}'::jsonb,
        receipt || '{"renewal_date":"2026-02-30"}'::jsonb,
        receipt || jsonb_build_object('renewal_date',(current_date-1)::text),
        receipt || '{"source_url":"https://mail.google.com.evil.invalid/mail/"}'::jsonb,
        receipt || '{"user_id":1}'::jsonb,
        receipt || '{"currency":null}'::jsonb,
        receipt || '{"billing_cycle":"weekly"}'::jsonb
    ] loop
        begin
            perform public.import_subscription_receipts(jsonb_build_array(
                receipt || '{"source_id":"atomic","service_name":"Atomic Service"}'::jsonb,
                invalid_receipt));
            raise exception 'Invalid batch accepted';
        exception when check_violation then null;
        end;
        if exists(select 1 from public.subscription_receipts where source_id='atomic')
           or exists(select 1 from public.subscriptions where subscription_name='Atomic Service') then
            raise exception 'Partial batch persisted';
        end if;
    end loop;
    begin
        perform public.import_subscription_receipts(null);
        raise exception 'Null batch accepted';
    exception when check_violation then null;
    end;
    begin
        perform public.import_subscription_receipts((select jsonb_agg(receipt) from generate_series(1,101)));
        raise exception 'Oversized batch accepted';
    exception when check_violation then null;
    end;

    delete from public.subscriptions where subscription_id=imported_id;
    result := public.import_subscription_receipts(jsonb_build_array(receipt));
    if result->>'imported' <> '0' then raise exception 'Deleted service resurrected'; end if;

    -- The same external receipt ID is isolated between accounts.
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b)::text,true);
    insert into public.users(first_name,last_name) values('Import','Beta') returning user_id into user_b;
    select count(*) into total from public.subscription_receipts where user_id=user_a;
    if total <> 0 then raise exception 'Receipt IDs leaked between accounts'; end if;
    result := public.import_subscription_receipts(jsonb_build_array(receipt));
    if result->>'imported' <> '1' then raise exception 'Cross-account deduplication occurred'; end if;
    begin
        insert into public.subscription_receipts(user_id,source_id) values(user_a,'spoofed');
        raise exception 'Cross-account ledger insert accepted';
    exception when insufficient_privilege then null;
    end;
    select count(*) into total from public.subscriptions;
    if total <> 1 then raise exception 'Subscriptions leaked between accounts'; end if;

    perform set_config('request.jwt.claim.sub','',true);
    perform set_config('request.jwt.claims','{}',true);
    begin
        perform public.import_subscription_receipts('[]'::jsonb);
        raise exception 'Missing identity accepted';
    exception when insufficient_privilege then null;
    end;
    execute 'set local role anon';
    begin
        perform public.import_subscription_receipts('[]'::jsonb);
        raise exception 'Anonymous RPC accepted';
    exception when insufficient_privilege then null;
    end;
    execute 'reset role';
end;
$contract$;
rollback;
select 'subscription import: idempotency, edits, validation, rollback, deletion and ownership passed' as result;
