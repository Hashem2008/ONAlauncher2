#!/usr/bin/env bash
# ONA: builds the native part of sqlite-jdbc (github.com/xerial/sqlite-jdbc, Apache 2.0; SQLite is
# public domain) for Android, with the JNI names of Distant Horizons' renamed copy.
#
# Distant Horizons 3.3.x bundles sqlite-jdbc 3.53.2.1 renamed to "dh_sqlite" and dropped the
# Linux-Android natives that 3.0.x still had: on the phone DH stops the game at start
# ("No native library found for os.name=Linux-Android, os.arch=aarch64"). Its loader reads the
# properties dh_sqlite.lib.path / dh_sqlite.lib.name first; the launcher points them at this build
# (game/version/mod/ZstdJniSupport.kt, sqlite part).
#
# Output: libona_sqlitejdbc_dh.so (Java_dh_1sqlite_core_NativeDB_..., classes dh_sqlite/...)
# Compiled like sqlite-jdbc's own Makefile (same SQLITE_* flags, extension functions, JDBC_EXTENSIONS).
#
# usage: build.sh <work dir> <output dir> [abi: arm64-v8a (default) | armeabi-v7a | x86_64 | x86]
# Host test: HOST_CC=gcc JNI_INCLUDES="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux" build.sh ...
set -euo pipefail

WORK=$(realpath -m "$1")
OUTDIR=$(realpath -m "$2")
JDBC_TAG=3.53.2.1
SQLITE_VERSION=3530200
ABI=${3:-arm64-v8a}
if [ -n "${HOST_CC:-}" ]; then
  CC="$HOST_CC"
  NM=nm
  STRIP=strip
  LIBS="-lpthread -lm -ldl"
else
  NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must point to an Android NDK}
  BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
  case "$ABI" in
    arm64-v8a)   CC="$BIN/aarch64-linux-android26-clang" ;;
    armeabi-v7a) CC="$BIN/armv7a-linux-androideabi26-clang" ;;
    x86_64)      CC="$BIN/x86_64-linux-android26-clang" ;;
    x86)         CC="$BIN/i686-linux-android26-clang" ;;
    *) echo "unknown ABI $ABI" >&2; exit 1 ;;
  esac
  NM="$BIN/llvm-nm"
  STRIP="$BIN/llvm-strip"
  LIBS="-lm -ldl -llog"
fi
echo "Building sqlite-jdbc $JDBC_TAG (SQLite $SQLITE_VERSION) for $ABI"

mkdir -p "$WORK"
cd "$WORK"
JDBC="$WORK/sqlite-jdbc-$JDBC_TAG"
if [ ! -f "$JDBC/src/main/java/org/sqlite/core/NativeDB.c" ]; then
  rm -rf "$JDBC"
  if curl -fsSL "https://github.com/xerial/sqlite-jdbc/archive/refs/tags/$JDBC_TAG.tar.gz" -o jdbc.tar.gz; then
    tar -xzf jdbc.tar.gz "sqlite-jdbc-$JDBC_TAG/src/main/java/org/sqlite/core/NativeDB.c" "sqlite-jdbc-$JDBC_TAG/src/main/ext"
  else
    git clone -q --depth 1 --branch "$JDBC_TAG" https://github.com/xerial/sqlite-jdbc.git "$JDBC"
  fi
fi
AMAL="$WORK/sqlite-amalgamation-$SQLITE_VERSION"
if [ ! -f "$AMAL/sqlite3.c" ]; then
  for year in 2026 2025 2027; do
    if curl -fsSL "https://www.sqlite.org/$year/sqlite-amalgamation-$SQLITE_VERSION.zip" -o amal.zip; then break; fi
    rm -f amal.zip
  done
  [ -f amal.zip ] || { echo "SQLite amalgamation $SQLITE_VERSION not found" >&2; exit 1; }
  unzip -q -o amal.zip
fi

GEN="$WORK/gen-$ABI"
rm -rf "$GEN"
mkdir -p "$GEN"
# sqlite3.c the way sqlite-jdbc's Makefile prepares it
perl -p -e "s/sqlite3_api;/sqlite3_api = 0;/g" "$AMAL/sqlite3ext.h" > "$GEN/sqlite3ext.h"
perl -p -e "s/^opendb_out:/  if(!db->mallocFailed && rc==SQLITE_OK){ rc = RegisterExtensionFunctions(db); }\nopendb_out:/;" \
  "$AMAL/sqlite3.c" > "$GEN/sqlite3.c.tmp"
perl -p -e "s/^(static const char \* const sqlite3azCompileOpt.+)\$/\1\n\n\/* This has been automatically added by sqlite-jdbc *\/\n  \"JDBC_EXTENSIONS\",/;" \
  "$GEN/sqlite3.c.tmp" > "$GEN/sqlite3.c"
cat "$JDBC"/src/main/ext/*.c >> "$GEN/sqlite3.c"
cp "$AMAL/sqlite3.h" "$GEN/"
# NativeDB.c with Distant Horizons' names; it only needs jni.h from the generated header
sed -e "s/Java_org_sqlite_/Java_dh_1sqlite_/g" -e "s#org/sqlite/#dh_sqlite/#g" \
  "$JDBC/src/main/java/org/sqlite/core/NativeDB.c" > "$GEN/NativeDB.c"
printf '#include <jni.h>\n' > "$GEN/NativeDB.h"

SQLITE_FLAGS="-DSQLITE_ENABLE_LOAD_EXTENSION=1 -DSQLITE_HAVE_ISNAN -DHAVE_USLEEP=1 -DSQLITE_ENABLE_COLUMN_METADATA \
 -DSQLITE_CORE -DSQLITE_ENABLE_FTS3 -DSQLITE_ENABLE_FTS3_PARENTHESIS -DSQLITE_ENABLE_FTS5 -DSQLITE_ENABLE_RTREE \
 -DSQLITE_ENABLE_PERCENTILE -DSQLITE_ENABLE_STAT4 -DSQLITE_ENABLE_DBSTAT_VTAB -DSQLITE_ENABLE_MATH_FUNCTIONS \
 -DSQLITE_THREADSAFE=1 -DSQLITE_DEFAULT_MEMSTATUS=0 -DSQLITE_DEFAULT_FILE_PERMISSIONS=0666 \
 -DSQLITE_MAX_VARIABLE_NUMBER=250000 -DSQLITE_MAX_MMAP_SIZE=1099511627776 -DSQLITE_MAX_LENGTH=2147483647 \
 -DSQLITE_MAX_COLUMN=32767 -DSQLITE_MAX_SQL_LENGTH=1073741824 -DSQLITE_MAX_FUNCTION_ARG=127 \
 -DSQLITE_MAX_ATTACHED=125 -DSQLITE_MAX_PAGE_COUNT=4294967294 -DSQLITE_DISABLE_PAGECACHE_OVERFLOW_STATS \
 -DSQLITE_ENABLE_UPDATE_DELETE_LIMIT"
CFLAGS="-Os -fPIC -fvisibility=hidden -Wno-implicit-function-declaration -I$GEN ${JNI_INCLUDES:-}"

# shellcheck disable=SC2086
"$CC" $CFLAGS $SQLITE_FLAGS -c "$GEN/sqlite3.c" -o "$GEN/sqlite3.o"
# shellcheck disable=SC2086
"$CC" $CFLAGS -c "$GEN/NativeDB.c" -o "$GEN/NativeDB.o"

NAME=libona_sqlitejdbc_dh.so
OUT="$OUTDIR/$NAME"
mkdir -p "$OUTDIR"
# shellcheck disable=SC2086
"$CC" -shared -Wl,--no-undefined -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 -Wl,-soname,"$NAME" \
  -o "$OUT" "$GEN/NativeDB.o" "$GEN/sqlite3.o" $LIBS
"$STRIP" --strip-unneeded "$OUT"

exports=$("$NM" -D --defined-only "$OUT" | grep -c " Java_dh_1sqlite_" || true)
if [ "$exports" -lt 55 ]; then
  echo "$NAME has only $exports JNI entry points" >&2
  exit 1
fi
if grep -q "org/sqlite/" "$OUT"; then
  echo "$NAME still names org/sqlite classes" >&2
  exit 1
fi
echo "Built $OUT ($(stat -c %s "$OUT") bytes, $exports JNI entry points)"
