#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
export PYTHONPATH="$project_dir/integrations/gitlab${PYTHONPATH:+:$PYTHONPATH}"
export PYTHONDONTWRITEBYTECODE=1
python3 -m unittest discover -s "$project_dir/integrations/gitlab/tests" -v
