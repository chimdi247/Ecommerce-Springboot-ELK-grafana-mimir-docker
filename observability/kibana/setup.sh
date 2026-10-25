#!/bin/sh
# One-shot: waits for Kibana, then creates the "ecommerce-logs-*" data view and imports the saved
# searches + "Ecommerce - Logs overview" dashboard. Safe to re-run (overwrites).
set -eu
KIBANA="${KIBANA_URL:-http://kibana:5601}"

echo "waiting for Kibana at $KIBANA ..."
until curl -fsS "$KIBANA/api/status" 2>/dev/null | grep -q '"level":"available"'; do sleep 5; done

# stable id so the saved searches can reference it
curl -sS -X POST "$KIBANA/api/data_views/data_view" \
  -H 'kbn-xsrf: true' -H 'Content-Type: application/json' \
  -d '{"override": true, "data_view": {"id": "ecommerce-logs", "title": "ecommerce-logs-*", "name": "Ecommerce logs", "timeFieldName": "@timestamp"}}'
echo

curl -sS -X POST "$KIBANA/api/saved_objects/_import?overwrite=true" \
  -H 'kbn-xsrf: true' --form file=@/setup/saved-objects.ndjson
echo
echo "Kibana ready: open $KIBANA/app/dashboards#/view/ecommerce-logs-overview"
