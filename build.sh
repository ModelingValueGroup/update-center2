#!/usr/bin/env bash

set -ue

[[ $# -eq 0            ]] || { echo "Usage: $0" >&2 ; exit 1 ; }

echo "build with maven..." >&2
mvn -e clean install > mvn.log
