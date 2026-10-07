# ADR 0001: Preserve app IDs and use Supabase Auth for the MVP

Date: 2026-10-02

## Context

The latest Deliverable 4 plan locks a Java/XML Android client and direct Supabase CRUD. The connected Cost Control database already has seven related tables using bigint IDs, one legacy app profile, no Supabase Auth users and no row-level policies. Replacing those tables would unnecessarily break the existing schema and lose the profile. Editable reminders and activity history are required separately from delivered notifications.

## Decision

Retain the core tables and identifiers. Link `users.auth_user_id` to the Supabase Auth UUID; app foreign keys continue to use the bigint `user_id`. New profile identity/email come from the authenticated JWT. Preserve legacy profiles unlinked until an administrator verifies ownership. Supabase Auth alone owns new credentials; exclude the legacy password column from client grants.

Add `reminders`, `user_settings` and `activity_logs` for the current MVP. Enforce ownership through RLS and cross-record ownership through composite foreign keys. Apply narrow column privileges, keep recommendations/notification delivery backend-written, and record activity through private, non-callable trigger functions. Keep CRUD outside the optional AI backend; Android accesses protected Supabase tables directly.

## Alternatives considered

- Recreate every table with UUID primary keys: simpler identifiers but an unnecessary incompatible rewrite.
- Add a parallel profiles/subscription schema: duplicate ownership and unclear canonical records.
- Link legacy profiles by matching emails automatically: risks claiming existing data without verifying ownership.
- Store client-supplied activity history: allows forged or missing events and breaks transaction integrity.
- Build provider connectors or another CRUD backend now: outside the latest MVP scope.

## Consequences

The client must distinguish Auth UUIDs from app bigint IDs and select safe profile columns explicitly. Legacy profile reconciliation is administrative. Composite foreign keys reject cross-user links even from backend writers. Activity history remains after entity deletion. Existing naive timestamps are treated as UTC. Auth and table setup do not by themselves implement Android screens, AI execution, scheduling or notification delivery.
