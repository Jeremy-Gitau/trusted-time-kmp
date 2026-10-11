#!/usr/bin/env bash
# Formats every Kotlin file in the project with ktlint, auto-fixing what it can,
# then runs ktlintCheck so anything left over (can't be auto-corrected) is visible.
set -uo pipefail
# shellcheck disable=SC2164
cd "$(dirname "${BASH_SOURCE[0]}")"
./gradlew ktlintFormat
./gradlew ktlintCheck
