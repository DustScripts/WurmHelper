#!/usr/bin/env bash

set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
launcher='/c/Program Files (x86)/Steam/steamapps/common/Wurm Unlimited/WurmLauncher'
launcher_win='C:/Program Files (x86)/Steam/steamapps/common/Wurm Unlimited/WurmLauncher'
installed_jar="$launcher/mods/WurmBot/WurmBot.jar"
dist_dir="$repo_dir/dist"
dist_jar="$dist_dir/WurmBot.jar"
classes="$repo_dir/build/paving-classes"
source_file="$repo_dir/main/java/net/ildar/wurm/bot/PavingBot.java"
repo_win="$(cygpath -m "$repo_dir")"
classes_win="$(cygpath -m "$classes")"
source_file_win="$(cygpath -m "$source_file")"
dist_jar_win="$repo_win/dist/WurmBot.jar"
classpath="$dist_jar_win;$launcher_win/client-patched.jar;$launcher_win/common.jar;$launcher_win/modlauncher.jar;$launcher_win/javassist.jar"

if [[ ! -f "$installed_jar" ]]; then
    echo "Error: installed WurmBot JAR was not found:"
    echo "  $installed_jar"
    exit 1
fi

if [[ ! -f "$source_file" ]]; then
    echo "Error: PavingBot source was not found:"
    echo "  $source_file"
    exit 1
fi

mkdir -p "$dist_dir" "$classes"

# Start from the installed working mod if no distribution JAR exists yet.
if [[ ! -f "$dist_jar" ]]; then
    cp "$installed_jar" "$dist_jar"
fi

rm -rf "$classes"
mkdir -p "$classes"

echo "Compiling PavingBot.java..."
javac -source 8 -target 8 -encoding UTF-8 \
    -cp "$classpath" \
    -d "$classes_win" \
    "$source_file_win"

echo "Updating dist/WurmBot.jar..."
jar uf "$dist_jar" -C "$classes" net/ildar/wurm/bot

echo "Build complete:"
echo "  $dist_jar"

if [[ "${1:-}" == "--install" ]]; then
    backup_jar="$installed_jar.before-paving-build"
    echo "Backing up installed JAR to:"
    echo "  $backup_jar"
    cp "$installed_jar" "$backup_jar"

    echo "Installing updated JAR..."
    cp "$dist_jar" "$installed_jar"
    echo "Install complete. Run patcher.bat before starting Wurm Unlimited."
elif [[ $# -gt 0 ]]; then
    echo "Error: unknown option '$1'"
    echo "Usage: ./build-paving.sh [--install]"
    exit 1
else
    echo "To also install it into Wurm Unlimited, run:"
    echo "  ./build-paving.sh --install"
fi
