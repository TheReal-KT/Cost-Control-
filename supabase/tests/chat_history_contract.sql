-- Each named block can be run independently by a privileged SQL runner.
-- Fixture rows roll back; auth identity sequence values may still advance.

-- contract: idempotent-turn-retries
begin;
do $contract$
declare
    auth_id uuid := gen_random_uuid();
    auth_email text := gen_random_uuid()::text || '@example.invalid';
    app_user_id bigint;
    v_conversation_id uuid := gen_random_uuid();
    v_turn_id uuid := gen_random_uuid();
    saved record;
    initial_created_at timestamptz;
    row_count integer;
begin
    insert into auth.users(id,aud,role,email)
        values(auth_id,'authenticated','authenticated',auth_email);
    perform set_config('request.jwt.claim.sub',auth_id::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_id,'email',auth_email,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha') returning user_id into app_user_id;

    select * into saved from public.save_chat_turn(v_conversation_id,v_turn_id,'Original title','Question','',false);
    if saved.status <> 'incomplete' or saved.answer <> '' then raise exception 'Initial partial turn was not saved'; end if;
    initial_created_at := saved.created_at;
    select * into saved from public.save_chat_turn(v_conversation_id,v_turn_id,'Replacement title','Question','Final answer',true);
    if saved.status <> 'complete' or saved.answer <> 'Final answer' or saved.created_at <> initial_created_at then
        raise exception 'Retry did not complete the stable turn';
    end if;
    select * into saved from public.save_chat_turn(v_conversation_id,v_turn_id,'Stale title','Question','Stale answer',false);
    if saved.status <> 'complete' or saved.answer <> 'Final answer' then raise exception 'Stale retry downgraded a completed turn'; end if;
    select count(*) into row_count from public.chat_conversations where user_id=app_user_id and conversation_id=v_conversation_id;
    if row_count <> 1 then raise exception 'Retries created duplicate conversations'; end if;
    select count(*) into row_count from public.chat_turns where user_id=app_user_id and turn_id=v_turn_id;
    if row_count <> 1 then raise exception 'Retries created duplicate turns'; end if;
    if not exists(select 1 from public.chat_conversations where user_id=app_user_id and title='Original title') then
        raise exception 'A retry unexpectedly changed the conversation title';
    end if;
end;
$contract$;
rollback;
select 'idempotent-turn-retries passed' as result;

-- contract: validation-and-idempotency-conflict
begin;
do $contract$
declare
    auth_id uuid := gen_random_uuid();
    auth_email text := gen_random_uuid()::text || '@example.invalid';
    app_user_id bigint;
    v_conversation_id uuid := gen_random_uuid();
    v_turn_id uuid := gen_random_uuid();
    saved record;
    row_count integer;
begin
    insert into auth.users(id,aud,role,email) values(auth_id,'authenticated','authenticated',auth_email);
    perform set_config('request.jwt.claim.sub',auth_id::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_id,'email',auth_email,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha') returning user_id into app_user_id;
    select * into saved from public.save_chat_turn(v_conversation_id,v_turn_id,'Valid title','Question','Answer',true);

    begin
        perform 1 from public.save_chat_turn(v_conversation_id,v_turn_id,'Valid title','Different question','Answer',true);
        raise exception 'Turn UUID reuse with different content was accepted';
    exception when unique_violation then null;
    end;
    begin
        perform 1 from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),repeat('x',121),'Question','',false);
        raise exception 'Overlong title was accepted';
    exception when check_violation then null;
    end;
    begin
        perform 1 from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),'Title',repeat('x',4001),'',false);
        raise exception 'Overlong question was accepted';
    exception when check_violation then null;
    end;
    begin
        perform 1 from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),'Title','Question',repeat('x',16001),false);
        raise exception 'Overlong answer was accepted';
    exception when check_violation then null;
    end;
    begin
        perform 1 from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),'Title','   ','',false);
        raise exception 'Blank question was accepted';
    exception when check_violation then null;
    end;
    begin
        perform 1 from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),'Title',repeat(' ',4001),'',false);
        raise exception 'Oversized whitespace question was accepted';
    exception when check_violation then null;
    end;
    begin
        insert into public.chat_turns(user_id,conversation_id,turn_id,question,answer,status)
            values(app_user_id,v_conversation_id,gen_random_uuid(),'Question','','pending');
        raise exception 'Unknown turn status was accepted';
    exception when check_violation then null;
    end;
    select count(*) into row_count from public.chat_turns where user_id=app_user_id and conversation_id=v_conversation_id;
    if row_count <> 1 then raise exception 'Rejected writes changed saved turn data'; end if;
end;
$contract$;
rollback;
select 'validation-and-idempotency-conflict passed' as result;

-- contract: owner-read-delete-insert-isolation
begin;
do $contract$
declare
    auth_a uuid := gen_random_uuid();
    auth_b uuid := gen_random_uuid();
    email_a text := gen_random_uuid()::text || '@example.invalid';
    email_b text := gen_random_uuid()::text || '@example.invalid';
    user_a bigint;
    v_conversation_id uuid := gen_random_uuid();
    v_turn_id uuid := gen_random_uuid();
    changed_count integer;
begin
    insert into auth.users(id,aud,role,email) values
        (auth_a,'authenticated','authenticated',email_a),(auth_b,'authenticated','authenticated',email_b);
    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha') returning user_id into user_a;
    execute 'reset role';
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Beta');

    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    perform 1 from public.save_chat_turn(v_conversation_id,v_turn_id,'Alpha chat','Question','Answer',true);
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    select count(*) into changed_count from public.chat_conversations where user_id=user_a;
    if changed_count <> 0 then raise exception 'Alpha conversations leaked'; end if;
    select count(*) into changed_count from public.chat_turns where user_id=user_a;
    if changed_count <> 0 then raise exception 'Alpha turns leaked'; end if;
    delete from public.chat_conversations where user_id=user_a and conversation_id=v_conversation_id;
    get diagnostics changed_count = row_count;
    if changed_count <> 0 then raise exception 'Cross-owner delete'; end if;
    begin
        insert into public.chat_conversations(user_id,conversation_id,title) values(user_a,gen_random_uuid(),'Spoof');
        raise exception 'Spoofed owner accepted';
    exception when insufficient_privilege then null;
    end;
    execute 'reset role';
    if not exists(select 1 from public.chat_conversations where user_id=user_a and conversation_id=v_conversation_id)
       or not exists(select 1 from public.chat_turns where user_id=user_a and turn_id=v_turn_id) then
        raise exception 'Beta affected Alpha history';
    end if;
end;
$contract$;
rollback;
select 'owner-read-delete-insert-isolation passed' as result;

-- contract: cross-owner-update-isolation
begin;
do $contract$
declare
    auth_a uuid := gen_random_uuid();
    auth_b uuid := gen_random_uuid();
    email_a text := gen_random_uuid()::text || '@example.invalid';
    email_b text := gen_random_uuid()::text || '@example.invalid';
    user_a bigint;
    v_conversation_id uuid := gen_random_uuid();
    v_turn_id uuid := gen_random_uuid();
    changed_count integer;
begin
    insert into auth.users(id,aud,role,email) values
        (auth_a,'authenticated','authenticated',email_a),(auth_b,'authenticated','authenticated',email_b);
    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha') returning user_id into user_a;
    execute 'reset role';
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Beta');

    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    perform 1 from public.save_chat_turn(v_conversation_id,v_turn_id,'Alpha chat','Question','Answer',true);
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    update public.chat_conversations set updated_at=now() where user_id=user_a and conversation_id=v_conversation_id;
    get diagnostics changed_count = row_count;
    if changed_count <> 0 then raise exception 'Cross-owner conversation update'; end if;
    update public.chat_turns set answer='forged' where user_id=user_a and turn_id=v_turn_id;
    get diagnostics changed_count = row_count;
    if changed_count <> 0 then raise exception 'Cross-owner turn update'; end if;
    execute 'reset role';
end;
$contract$;
rollback;
select 'cross-owner-update-isolation passed' as result;

-- contract: composite-owner-foreign-key-and-cascade
begin;
do $contract$
declare
    auth_a uuid := gen_random_uuid();
    auth_b uuid := gen_random_uuid();
    email_a text := gen_random_uuid()::text || '@example.invalid';
    email_b text := gen_random_uuid()::text || '@example.invalid';
    user_a bigint;
    user_b bigint;
    v_conversation_id uuid := gen_random_uuid();
    v_turn_id uuid := gen_random_uuid();
    saved record;
    changed_count integer;
begin
    insert into auth.users(id,aud,role,email) values
        (auth_a,'authenticated','authenticated',email_a),(auth_b,'authenticated','authenticated',email_b);
    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Alpha') returning user_id into user_a;
    execute 'reset role';
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    insert into public.users(first_name,last_name) values('Contract','Beta') returning user_id into user_b;
    execute 'reset role';

    perform set_config('request.jwt.claim.sub',auth_a::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_a,'email',email_a,'role','authenticated')::text,true);
    execute 'set local role authenticated';
    perform 1 from public.save_chat_turn(v_conversation_id,v_turn_id,'Alpha chat','Question','Answer',true);
    perform set_config('request.jwt.claim.sub',auth_b::text,true);
    perform set_config('request.jwt.claims',jsonb_build_object('sub',auth_b,'email',email_b,'role','authenticated')::text,true);
    begin
        insert into public.chat_turns(user_id,conversation_id,turn_id,question,answer,status)
            values(user_b,v_conversation_id,gen_random_uuid(),'Question','Answer','complete');
        raise exception 'Cross-owner conversation reference was accepted';
    exception when foreign_key_violation then null;
    end;
    select * into saved from public.save_chat_turn(v_conversation_id,v_turn_id,'Beta chat','Question','Beta answer',true);
    if saved.answer <> 'Beta answer' then raise exception 'Beta could not reuse UUIDs in its own owner scope'; end if;
    delete from public.chat_conversations where user_id=user_b and conversation_id=v_conversation_id;
    get diagnostics changed_count = row_count;
    if changed_count <> 1 then raise exception 'Beta conversation delete failed'; end if;
    select count(*) into changed_count from public.chat_turns where user_id=user_b and turn_id=v_turn_id;
    if changed_count <> 0 then raise exception 'Conversation deletion did not cascade to Beta turn'; end if;
    execute 'reset role';
    if not exists(select 1 from public.chat_conversations where user_id=user_a and conversation_id=v_conversation_id)
       or not exists(select 1 from public.chat_turns where user_id=user_a and turn_id=v_turn_id) then
        raise exception 'Beta deletion affected Alpha history';
    end if;
end;
$contract$;
rollback;
select 'composite-owner-foreign-key-and-cascade passed' as result;

-- contract: anonymous-access-denied
begin;
do $contract$
declare
    attempt text;
begin
    perform set_config('request.jwt.claim.sub','',true);
    perform set_config('request.jwt.claims','{}',true);
    execute 'set local role anon';
    foreach attempt in array array[
        'select conversation_id from public.chat_conversations',
        'select turn_id from public.chat_turns',
        'select * from public.save_chat_turn(gen_random_uuid(),gen_random_uuid(),''Title'',''Question'','''',false)'
    ] loop
        begin
            execute attempt;
            raise exception 'Anonymous chat history access was accepted: %',attempt;
        exception when insufficient_privilege then null;
        end;
    end loop;
end;
$contract$;
rollback;
select 'anonymous-access-denied passed' as result;
