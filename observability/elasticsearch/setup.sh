#!/bin/sh
# One-shot: waits for Elasticsearch, then installs the retention policy (ILM) and index template
# BEFORE Fluent Bit starts writing, so the first daily index already has the right settings.
set -eu
ES="${ELASTICSEARCH_URL:-http://elasticsearch:9200}"

echo "waiting for Elasticsearch at $ES ..."
until curl -fsS "$ES/_cluster/health?wait_for_status=yellow&timeout=5s" >/dev/null 2>&1; do sleep 3; done

curl -fsS -X PUT "$ES/_ilm/policy/ecommerce-logs" -H 'Content-Type: application/json' --data-binary @/setup/ilm-policy.json
echo
curl -fsS -X PUT "$ES/_index_template/ecommerce-logs" -H 'Content-Type: application/json' --data-binary @/setup/index-template.json
echo
echo "Elasticsearch ready: ILM policy + index template installed"
