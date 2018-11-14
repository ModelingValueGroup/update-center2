#!/usr/bin/env bash -ue

set -ue

[[ $# -eq 1            ]] || { echo "Usage: $0 <min-jenkins-version>"     >&2 ; exit 1 ; }
[[ -n "$1"             ]] || { echo "Non-empty minimum jenkins version"   >&2 ; exit 1 ; }

 MIN_VERSION="$1" # ex "2.151"
DOWNLOAD_DIR="tmp-plugins"

versionExists="$(curl -s 'https://repo.jenkins-ci.org/api/search/versions?g=org.jenkins-ci.main&a=jenkins-core&repos=releases' \
                    | jq --raw-output '.results[].version' \
                    | fgrep "$MIN_VERSION")"
[[ "$versionExists" != "" ]] ||  { echo "the jenkins version $MIN_VERSION does not exist" >&2 ; exit 1 ; }

./build.sh

echo "prepare download dir..." >&2
rm    -rf "$DOWNLOAD_DIR"
mkdir -p  "$DOWNLOAD_DIR"

echo "run to fill download dir..." >&2
echo >&2
java \
    -cp target/update-center2-*-bin*/update-center2-*.jar \
    org.jvnet.hudson.update_center.MainOnlyDownload \
    -version  "$MIN_VERSION" \
    -download "$DOWNLOAD_DIR"
