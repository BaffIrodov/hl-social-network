#!/bin/sh

(
    set -eu

    psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
        --set=replication_password="$REPLICATION_PASSWORD" <<'SQL'
CREATE ROLE replicator WITH LOGIN REPLICATION PASSWORD :'replication_password';
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SQL

    printf '\nhost replication replicator samenet scram-sha-256\n' >> "$PGDATA/pg_hba.conf"
)
