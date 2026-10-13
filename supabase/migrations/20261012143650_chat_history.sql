-- Native chat history is stored in owner-scoped conversations with paired question/answer turns.
create table public.chat_conversations (
    user_id bigint not null references public.users(user_id) on delete cascade,
    conversation_id uuid not null,
    title varchar(120) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chat_conversations_pkey primary key (user_id, conversation_id),
    constraint chat_conversations_title_check
        check (char_length(btrim(title)) between 1 and 120)
);

create index chat_conversations_user_updated_idx
    on public.chat_conversations(user_id, updated_at desc, conversation_id desc);

create table public.chat_turns (
    user_id bigint not null,
    conversation_id uuid not null,
    turn_id uuid not null,
    question text not null,
    answer text not null,
    status varchar(12) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chat_turns_pkey primary key (user_id, turn_id),
    constraint chat_turns_conversation_fkey
        foreign key (user_id, conversation_id)
        references public.chat_conversations(user_id, conversation_id) on delete cascade,
    constraint chat_turns_question_check
        check (char_length(question) between 1 and 4000 and char_length(btrim(question)) >= 1),
    constraint chat_turns_answer_check
        check (char_length(answer) <= 16000),
    constraint chat_turns_status_check
        check (status in ('complete', 'incomplete'))
);

create index chat_turns_conversation_created_idx
    on public.chat_turns(user_id, conversation_id, created_at desc, turn_id desc);

alter table public.chat_conversations enable row level security;
alter table public.chat_turns enable row level security;
revoke all on public.chat_conversations from public, anon, authenticated;
revoke all on public.chat_turns from public, anon, authenticated;

grant select, delete on public.chat_conversations to authenticated;
grant insert (user_id, conversation_id, title), update (updated_at)
    on public.chat_conversations to authenticated;
grant select on public.chat_turns to authenticated;
grant insert (user_id, conversation_id, turn_id, question, answer, status),
    update (answer, status, updated_at) on public.chat_turns to authenticated;

create policy chat_conversations_select_own on public.chat_conversations
    for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy chat_conversations_insert_own on public.chat_conversations
    for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy chat_conversations_update_own on public.chat_conversations
    for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy chat_conversations_delete_own on public.chat_conversations
    for delete to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

create policy chat_turns_select_own on public.chat_turns
    for select to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy chat_turns_insert_own on public.chat_turns
    for insert to authenticated
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));
create policy chat_turns_update_own on public.chat_turns
    for update to authenticated
    using (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())))
    with check (user_id in (select user_id from public.users where auth_user_id = (select auth.uid())));

create or replace function public.save_chat_turn(
    p_conversation_id uuid,
    p_turn_id uuid,
    p_title text,
    p_question text,
    p_answer text,
    p_complete boolean
)
returns table (
    conversation_id uuid,
    turn_id uuid,
    question text,
    answer text,
    status text,
    created_at timestamptz,
    updated_at timestamptz
)
language plpgsql
security invoker
set search_path = pg_catalog, public, auth
as $save_chat_turn$
declare
    owner_id bigint;
    saved_turn public.chat_turns%rowtype;
begin
    if auth.uid() is null then
        raise exception 'An authenticated user is required' using errcode = '42501';
    end if;

    select app_user.user_id into owner_id
    from public.users as app_user
    where app_user.auth_user_id = auth.uid();

    if owner_id is null then
        raise exception 'The authenticated user profile is required' using errcode = '42501';
    end if;
    if p_conversation_id is null or p_turn_id is null then
        raise exception 'Conversation and turn IDs are required' using errcode = '23514';
    end if;
    if p_title is null or char_length(btrim(p_title)) not between 1 and 120 then
        raise exception 'Conversation title must contain 1 to 120 characters' using errcode = '23514';
    end if;
    if p_question is null or char_length(p_question) not between 1 and 4000
       or char_length(btrim(p_question)) < 1 then
        raise exception 'Question must contain 1 to 4000 characters' using errcode = '23514';
    end if;
    if p_answer is null or char_length(p_answer) > 16000 then
        raise exception 'Answer must contain at most 16000 characters' using errcode = '23514';
    end if;
    if p_complete is null then
        raise exception 'Turn completion status is required' using errcode = '23514';
    end if;

    insert into public.chat_conversations as existing (user_id, conversation_id, title)
    values (owner_id, p_conversation_id, btrim(p_title))
    on conflict on constraint chat_conversations_pkey do update
        set updated_at = greatest(clock_timestamp(), existing.updated_at);

    insert into public.chat_turns as existing (
        user_id, conversation_id, turn_id, question, answer, status
    )
    values (
        owner_id, p_conversation_id, p_turn_id, p_question, p_answer,
        case when p_complete then 'complete' else 'incomplete' end
    )
    on conflict on constraint chat_turns_pkey do update set
        answer = case when existing.status = 'complete' then existing.answer else excluded.answer end,
        status = case when existing.status = 'complete' then existing.status else excluded.status end,
        updated_at = case when existing.status = 'complete'
            then existing.updated_at else greatest(clock_timestamp(), existing.updated_at) end
    where existing.conversation_id = excluded.conversation_id
      and existing.question = excluded.question
    returning existing.* into saved_turn;

    if not found then
        raise exception 'Turn ID is already associated with different content' using errcode = '23505';
    end if;

    return query select saved_turn.conversation_id, saved_turn.turn_id, saved_turn.question,
        saved_turn.answer, saved_turn.status::text, saved_turn.created_at, saved_turn.updated_at;
end;
$save_chat_turn$;

revoke all on function public.save_chat_turn(uuid, uuid, text, text, text, boolean)
    from public, anon, authenticated;
grant execute on function public.save_chat_turn(uuid, uuid, text, text, text, boolean)
    to authenticated;
