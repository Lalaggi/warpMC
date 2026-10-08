#!/usr/bin/env bash
# Upload build jars to GitLab Generic Package Registry
# Uses CI_JOB_TOKEN which has write_package permission by default
# Uploads with clean names (no version suffix) for easy release links
set -euo pipefail

REPO_URL="$CI_API_V4_URL/projects/$CI_PROJECT_ID/packages/generic/warpMC/$RELEASE_VERSION"

upload_as() {
  local file="$1"
  local target_name="$2"
  echo "Uploading $(basename "$file") as $target_name..."
  curl --fail --silent --show-error \
    --header "JOB-TOKEN: $CI_JOB_TOKEN" \
    --upload-file "$file" \
    "$REPO_URL/$target_name"
  echo "  -> $REPO_URL/$target_name"
}

echo "=== Uploading jars to Generic Package Registry (version: $RELEASE_VERSION) ==="

# Bundler jar (~95 MB) - PRIMARY runnable server (`java -jar` target)
for jar in purpur-server/build/libs/purpur-bundler-*.jar; do
  [ -f "$jar" ] && upload_as "$jar" "purpur-bundler.jar"
done
# WarpMC branded alias - same file as bundler, for `java -jar warpmc.jar`
if [ -f "warpmc.jar" ]; then
  upload_as "warpmc.jar" "warpmc.jar"
elif ls purpur-server/build/libs/purpur-bundler-*.jar 1>/dev/null 2>&1; then
  # fallback if warpmc.jar not pre-copied (e.g. local test)
  for jar in purpur-server/build/libs/purpur-bundler-*.jar; do
    [ -f "$jar" ] && upload_as "$jar" "warpmc.jar" && break
  done
fi

# Paperclip jar (~54 MB) - alternative runnable
for jar in purpur-server/build/libs/purpur-paperclip-*.jar; do
  [ -f "$jar" ] && upload_as "$jar" "purpur-paperclip.jar"
done

# API jar
for jar in purpur-api/build/libs/purpur-api-*.jar; do
  # skip -sources and -javadoc variants
  case "$(basename "$jar")" in
    *-sources.jar|*-javadoc.jar) continue ;;
  esac
  [ -f "$jar" ] && upload_as "$jar" "purpur-api.jar"
done

# Server jar (exclude bundler/paperclip already uploaded)
for jar in purpur-server/build/libs/purpur-server-*.jar; do
  name=$(basename "$jar")
  case "$name" in
    purpur-bundler-*|purpur-paperclip-*|warpmc-bundler.jar) ;;
    *) [ -f "$jar" ] && upload_as "$jar" "purpur-server.jar" ;;
  esac
done

echo "=== All uploads complete ==="
