#!/usr/bin/env bash
set -euo pipefail

# Scan tracked source/config files for high-signal credential formats only.
# This is intentionally narrow to avoid blocking legitimate identifiers or
# documentation text. CI release secrets remain GitHub Actions secrets.
PATTERN='BEGIN (RSA|EC|OPENSSH|DSA) PRIVATE KEY|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,}|AIza[0-9A-Za-z_-]{20,}|sk-[A-Za-z0-9]{20,}'

if git grep -nE "$PATTERN" -- ':!docs/archive' ':!.git' ; then
  echo "Potential credential material detected. Remove it from tracked files and rotate it if it was ever real."
  exit 1
fi

echo "Secret scan passed: no high-signal credential formats found."
