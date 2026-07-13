#!/usr/bin/env bash
# Tear down the guardian-infra EC2 instance. Calls deploy.sh --destroy.
set -euo pipefail
"$(dirname "$0")/deploy.sh" --destroy
