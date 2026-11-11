#!/bin/sh
set -eu

if [ ! -s "$PGDATA/PG_VERSION" ]; then
    if [ -e "$PGDATA" ]; then
        echo "Refusing to overwrite existing data directory $PGDATA" >&2
        exit 1
    fi

    # Publish a complete backup so an interrupted copy cannot be started as a cluster.
    backup_dir=$(mktemp -d "$(dirname "$PGDATA")/.basebackup.XXXXXX")
    trap 'rm -rf -- "$backup_dir"' EXIT
    trap 'exit 1' HUP INT TERM

    PGPASSWORD="$REPLICATION_PASSWORD" pg_basebackup --no-password \
        --dbname="host=primary port=5432 user=replicator application_name=$REPLICA_NAME" \
        --pgdata="$backup_dir" --checkpoint=fast --wal-method=stream --write-recovery-conf

    mv -- "$backup_dir" "$PGDATA"
    trap - EXIT HUP INT TERM
fi

exec docker-entrypoint.sh "$@"
