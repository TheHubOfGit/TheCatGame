#!/usr/bin/env bash
# Deploys the relay Worker and the thecatgame.pages.dev site.
set -euo pipefail
cd "$(dirname "$0")"
cp ../app/assets/index.html site/public/index.html
(cd relay && npx --yes wrangler@latest deploy)
(cd site && npx --yes wrangler@latest pages deploy --project-name thecatgame --branch main --commit-dirty=true)
