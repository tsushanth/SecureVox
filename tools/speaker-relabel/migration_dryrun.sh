#!/bin/bash
# Dry run of Room migration 4 -> 5 (adds transcript_segments.speakerSplit) against a v4-shaped database.
#
# Builds a v4 database with sqlite3, fills it, applies the exact ALTER TABLE that
# SecureVoxDatabase.MIGRATION_4_5 runs (read from the source, not retyped), checks every row
# survived with speakerSplit = 0, and that the migrated column matches what Room validates
# (INTEGER, NOT NULL, default 0) by comparing with a table created fresh from the v5 schema.
# Usage: tools/speaker-relabel/migration_dryrun.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=android/app/src/main/java/com/securevox/app/data/local/SecureVoxDatabase.kt
SQL=$(grep -A1 'const val ADD_SPEAKER_SPLIT_COLUMN_SQL' "$SRC" | tail -1 | sed -E 's/^[[:space:]]*"(.*)"[[:space:]]*$/\1/')
echo "migration SQL: $SQL"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

RECORDINGS='CREATE TABLE `recordings` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `audioFilePath` TEXT NOT NULL, `duration` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `transcriptionStatus` TEXT NOT NULL, `transcriptionProgress` INTEGER NOT NULL, `language` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`));'
FK='FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE'
V4_SEGMENTS='CREATE TABLE `transcript_segments` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `text` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `segmentIndex` INTEGER NOT NULL, `speaker` INTEGER NOT NULL DEFAULT -1, `words` TEXT NOT NULL DEFAULT '"''"', PRIMARY KEY(`id`), '"$FK"' );'
V5_SEGMENTS='CREATE TABLE `transcript_segments` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `text` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `segmentIndex` INTEGER NOT NULL, `speaker` INTEGER NOT NULL DEFAULT -1, `words` TEXT NOT NULL DEFAULT '"''"', `speakerSplit` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`), '"$FK"' );'
INDEX='CREATE INDEX `index_transcript_segments_recordingId` ON `transcript_segments` (`recordingId`);'

sqlite3 "$DIR/v4.db" "PRAGMA foreign_keys=ON; $RECORDINGS $V4_SEGMENTS $INDEX
INSERT INTO recordings VALUES ('r1','Standup','/a.wav',60000,1000,1,'COMPLETED',100,'en',0),('r2','Call','/b.wav',1000,10,2,'COMPLETED',100,'en',1);
INSERT INTO transcript_segments VALUES ('s1','r1','Hello there.',0,1500,0,-1,''),('s2','r1','Second speaker.',1500,3000,1,1,'[[\"Second\",1500,1800]]'),('s3','r2','It''s \"quoted\" é',0,900,0,0,'');"
COLS="id,recordingId,text,startTimeMs,endTimeMs,segmentIndex,speaker,words"
BEFORE=$(sqlite3 "$DIR/v4.db" "SELECT $COLS FROM transcript_segments ORDER BY id")

sqlite3 "$DIR/v4.db" "$SQL;"
AFTER=$(sqlite3 "$DIR/v4.db" "SELECT $COLS FROM transcript_segments ORDER BY id")
[ "$BEFORE" == "$AFTER" ] || { echo "FAIL: existing rows changed"; exit 1; }
[ "$(sqlite3 "$DIR/v4.db" 'SELECT count(*) FROM recordings')" == 2 ] || { echo "FAIL: recordings lost"; exit 1; }
[ "$(sqlite3 "$DIR/v4.db" "SELECT count(*) FROM transcript_segments WHERE speakerSplit = 0")" == 3 ] || { echo "FAIL: old rows should have speakerSplit 0"; exit 1; }

# New rows work with and without the column.
sqlite3 "$DIR/v4.db" "INSERT INTO transcript_segments(id,recordingId,text,startTimeMs,endTimeMs,segmentIndex,speaker,words,speakerSplit) VALUES ('s4','r1','Hi',0,1,2,0,'',1);
INSERT INTO transcript_segments(id,recordingId,text,startTimeMs,endTimeMs,segmentIndex) VALUES ('s5','r1','No flag',0,1,3);"
[ "$(sqlite3 "$DIR/v4.db" "SELECT speakerSplit FROM transcript_segments WHERE id='s5'")" == "0" ] || { echo "FAIL: default not applied"; exit 1; }

# Column definition must equal a fresh v5 table (what Room's TableInfo check compares).
sqlite3 "$DIR/v5.db" "$RECORDINGS $V5_SEGMENTS $INDEX"
MIGRATED=$(sqlite3 "$DIR/v4.db" "SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('transcript_segments') ORDER BY name")
FRESH=$(sqlite3 "$DIR/v5.db" "SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('transcript_segments') ORDER BY name")
[ "$MIGRATED" == "$FRESH" ] || { echo "FAIL: migrated schema differs from fresh v5"; diff <(echo "$MIGRATED") <(echo "$FRESH"); exit 1; }
[ "$(sqlite3 "$DIR/v4.db" 'PRAGMA foreign_key_check; PRAGMA integrity_check' | tr -d '\n')" == "ok" ] || { echo "FAIL: integrity"; exit 1; }
echo "speakerSplit column as migrated: $(echo "$MIGRATED" | grep '^speakerSplit')"
echo "OK: 3 pre-existing segments + 2 recordings survived, speakerSplit defaults to 0, schema matches v5"
