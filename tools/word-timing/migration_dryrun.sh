#!/bin/bash
# Dry run of Room migration 3 -> 4 (adds transcript_segments.words) against a v3-shaped database.
#
# Builds a v3 database with sqlite3 from the entity schema as it was in version 3, fills it with
# rows, applies the exact ALTER TABLE that SecureVoxDatabase.MIGRATION_3_4 runs (read from the
# source, not retyped), then checks that every row survived and that the resulting column matches
# what Room validates (TEXT, NOT NULL, default '') by comparing with a database created fresh from
# the v4 schema. Usage: tools/word-timing/migration_dryrun.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=android/app/src/main/java/com/securevox/app/data/local/SecureVoxDatabase.kt
SQL=$(grep -A1 'const val ADD_WORDS_COLUMN_SQL' "$SRC" | tail -1 | sed -E 's/^[[:space:]]*"(.*)"[[:space:]]*$/\1/')
echo "migration SQL: $SQL"
DIR=$(mktemp -d)
trap 'rm -rf "$DIR"' EXIT

RECORDINGS='CREATE TABLE `recordings` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `audioFilePath` TEXT NOT NULL, `duration` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `transcriptionStatus` TEXT NOT NULL, `transcriptionProgress` INTEGER NOT NULL, `language` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, PRIMARY KEY(`id`));'
V3_SEGMENTS='CREATE TABLE `transcript_segments` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `text` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `segmentIndex` INTEGER NOT NULL, `speaker` INTEGER NOT NULL DEFAULT -1, PRIMARY KEY(`id`), FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );'
V4_SEGMENTS='CREATE TABLE `transcript_segments` (`id` TEXT NOT NULL, `recordingId` TEXT NOT NULL, `text` TEXT NOT NULL, `startTimeMs` INTEGER NOT NULL, `endTimeMs` INTEGER NOT NULL, `segmentIndex` INTEGER NOT NULL, `speaker` INTEGER NOT NULL DEFAULT -1, `words` TEXT NOT NULL DEFAULT '"''"', PRIMARY KEY(`id`), FOREIGN KEY(`recordingId`) REFERENCES `recordings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );'
INDEX='CREATE INDEX `index_transcript_segments_recordingId` ON `transcript_segments` (`recordingId`);'

sqlite3 "$DIR/v3.db" "PRAGMA foreign_keys=ON; $RECORDINGS $V3_SEGMENTS $INDEX
INSERT INTO recordings VALUES ('r1','Standup','/a.wav',60000,1000,1,'COMPLETED',100,'en',0),('r2','Call','/b.wav',1000,10,2,'COMPLETED',100,'en',1);
INSERT INTO transcript_segments VALUES ('s1','r1','Hello there.',0,1500,0,-1),('s2','r1','Second speaker.',1500,3000,1,1),('s3','r2','It''s \"quoted\" é',0,900,0,0);"
BEFORE=$(sqlite3 "$DIR/v3.db" "SELECT id,recordingId,text,startTimeMs,endTimeMs,segmentIndex,speaker FROM transcript_segments ORDER BY id")

sqlite3 "$DIR/v3.db" "$SQL;"
AFTER=$(sqlite3 "$DIR/v3.db" "SELECT id,recordingId,text,startTimeMs,endTimeMs,segmentIndex,speaker FROM transcript_segments ORDER BY id")
[ "$BEFORE" == "$AFTER" ] || { echo "FAIL: existing rows changed"; exit 1; }
[ "$(sqlite3 "$DIR/v3.db" 'SELECT count(*) FROM recordings')" == 2 ] || { echo "FAIL: recordings lost"; exit 1; }
[ "$(sqlite3 "$DIR/v3.db" "SELECT count(*) FROM transcript_segments WHERE words = ''")" == 3 ] || { echo "FAIL: old rows should have empty words"; exit 1; }

# New rows with and without words work after the migration.
sqlite3 "$DIR/v3.db" "INSERT INTO transcript_segments(id,recordingId,text,startTimeMs,endTimeMs,segmentIndex,speaker,words) VALUES ('s4','r1','Hi',0,1,2,-1,'[[\"Hi\",0,1]]');
INSERT INTO transcript_segments(id,recordingId,text,startTimeMs,endTimeMs,segmentIndex) VALUES ('s5','r1','No words',0,1,3);"
[ "$(sqlite3 "$DIR/v3.db" "SELECT words FROM transcript_segments WHERE id='s5'")" == "" ] || { echo "FAIL: default not applied"; exit 1; }

# Column definition must equal a fresh v4 table (what Room's TableInfo check compares).
sqlite3 "$DIR/v4.db" "$RECORDINGS $V4_SEGMENTS $INDEX"
MIGRATED=$(sqlite3 "$DIR/v3.db" "SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('transcript_segments') ORDER BY name")
FRESH=$(sqlite3 "$DIR/v4.db" "SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('transcript_segments') ORDER BY name")
[ "$MIGRATED" == "$FRESH" ] || { echo "FAIL: migrated schema differs from fresh v4"; diff <(echo "$MIGRATED") <(echo "$FRESH"); exit 1; }
[ "$(sqlite3 "$DIR/v3.db" 'PRAGMA foreign_key_check; PRAGMA integrity_check' | tr -d '\n')" == "ok" ] || { echo "FAIL: integrity"; exit 1; }
echo "words column as migrated: $(echo "$MIGRATED" | grep '^words')"
echo "OK: 3 pre-existing segments + 2 recordings survived, words defaults to '', schema matches v4"
