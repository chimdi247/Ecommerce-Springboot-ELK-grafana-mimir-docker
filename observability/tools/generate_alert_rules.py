#!/usr/bin/env python3
"""Generates grafana/provisioning/alerting/alert-rules.yaml (Grafana-managed alerts).
Run: python3 observability/tools/generate_alert_rules.py
Each rule: A = Mimir query (instant) -> B = reduce(last) -> C = threshold.
Annotation text is static on purpose (provisioning files interpolate `$VAR`, so no Go-template `$labels`);
Grafana's email lists all labels (container name, service ...) of the firing instance anyway."""
import pathlib
import yaml

C = 'name=~"ecommerce-.+"'

RESOURCE = [
    ("ecom-node-cpu-high", "Node CPU above 40%",
     '100 - avg(rate(node_cpu_seconds_total{mode="idle"}[2m])) * 100',
     "gt", 40, "2m", "warning", "Host CPU utilisation is above 40%.", "OK"),
    ("ecom-node-memory-high", "Node memory above 40%",
     '100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)',
     "gt", 40, "2m", "warning", "Host memory utilisation is above 40%.", "OK"),
    ("ecom-container-cpu-high", "Container CPU above 40% of its limit",
     f'100 * sum by (name) (rate(container_cpu_usage_seconds_total{{{C}}}[2m])) '
     f'/ max by (name) (container_spec_cpu_quota{{{C}}} / container_spec_cpu_period{{{C}}} > 0)',
     "gt", 40, "2m", "warning", "A container is using more than 40% of its CPU limit (see the 'name' label).", "OK"),
    ("ecom-container-memory-high", "Container memory above 40% of its limit",
     f'100 * max by (name) (container_memory_working_set_bytes{{{C}}}) '
     f'/ max by (name) (container_spec_memory_limit_bytes{{{C}}} > 0)',
     "gt", 40, "2m", "warning", "A container is using more than 40% of its memory limit (see the 'name' label).", "OK"),
]

SERVICE = [
    ("ecom-service-down", "Service health probe failing",
     'min by (service) (probe_success{job="blackbox-http"})',
     "lt", 1, "1m", "critical", "A health probe (backend / frontend) has been failing for 1 minute.", "Alerting"),
    ("ecom-high-error-rate", "HTTP 5xx error rate above 5%",
     '100 * sum(rate(http_server_request_duration_count{http_response_status_code=~"5.."}[5m])) '
     '/ sum(rate(http_server_request_duration_count[5m]))',
     "gt", 5, "5m", "critical", "More than 5% of backend requests fail with 5xx (availability SLO at risk).", "OK"),
    ("ecom-high-latency-p95", "HTTP p95 latency above 1s",
     'histogram_quantile(0.95, sum by (le) (rate(http_server_request_duration_bucket[5m])))',
     "gt", 1, "5m", "warning", "Backend p95 latency is above 1 second (latency SLO at risk).", "OK"),
    ("ecom-jvm-heap-high", "JVM heap above 85% of max",
     '100 * max(jvm_memory_used{jvm_memory_type="heap"}) / max(jvm_memory_limit{jvm_memory_type="heap"})',
     "gt", 85, "5m", "warning", "The backend JVM heap is close to its maximum (GC pressure / OOM risk).", "OK"),
]

BUSINESS = [
    ("ecom-payment-failure-ratio", "Payment failure ratio above 20%",
     '100 * sum(increase(ecommerce_payments{outcome="failed"}[15m])) '
     '/ (sum(increase(ecommerce_payments{outcome=~"failed|succeeded"}[15m])) > 3)',
     "gt", 20, "5m", "critical", "More than 20% of payment attempts in the last 15 minutes failed.", "OK"),
    ("ecom-email-failures", "Payment confirmation e-mails failing",
     'sum(increase(ecommerce_notifications{outcome="failed"}[15m]))',
     "gt", 0, "1m", "warning", "At least one payment confirmation e-mail failed to send (check SMTP credentials).", "OK"),
    ("ecom-mysql-connections-high", "MySQL connections above 80% of max",
     '100 * mysql_global_status_threads_connected / mysql_global_variables_max_connections',
     "gt", 80, "5m", "warning", "MySQL is close to max_connections.", "OK"),
    ("ecom-redis-memory-high", "Redis memory above 80% of maxmemory",
     '100 * redis_memory_used_bytes / redis_memory_max_bytes',
     "gt", 80, "5m", "warning", "Redis is close to its maxmemory limit (evictions imminent).", "OK"),
    ("ecom-rate-limit-spike", "Rate limiter rejecting many requests",
     'sum(increase(ecommerce_ratelimit_rejected[5m]))',
     "gt", 100, "2m", "warning", "More than 100 requests were rejected with HTTP 429 in 5 minutes (abuse or too-low limit).", "OK"),
]


def rule(uid, title, expr, op, threshold, for_, severity, summary, no_data):
    return {
        "uid": uid, "title": title, "condition": "C",
        "data": [
            {"refId": "A", "relativeTimeRange": {"from": 600, "to": 0}, "datasourceUid": "mimir",
             "model": {"datasource": {"type": "prometheus", "uid": "mimir"}, "editorMode": "code", "expr": expr,
                       "instant": True, "range": False, "intervalMs": 1000, "maxDataPoints": 43200, "refId": "A"}},
            {"refId": "B", "datasourceUid": "__expr__",
             "model": {"type": "reduce", "expression": "A", "reducer": "last", "settings": {"mode": "dropNN"},
                       "refId": "B", "datasource": {"type": "__expr__", "uid": "__expr__"}}},
            {"refId": "C", "datasourceUid": "__expr__",
             "model": {"type": "threshold", "expression": "B",
                       "conditions": [{"evaluator": {"type": op, "params": [threshold]}}],
                       "refId": "C", "datasource": {"type": "__expr__", "uid": "__expr__"}}},
        ],
        "noDataState": no_data, "execErrState": "Error", "for": for_,
        "annotations": {"summary": title, "description": summary},
        "labels": {"severity": severity}, "isPaused": False,
    }


doc = {"apiVersion": 1, "groups": [
    {"orgId": 1, "name": "resource-utilization", "folder": "Ecommerce Alerts", "interval": "1m", "rules": [rule(*r) for r in RESOURCE]},
    {"orgId": 1, "name": "service-slo", "folder": "Ecommerce Alerts", "interval": "1m", "rules": [rule(*r) for r in SERVICE]},
    {"orgId": 1, "name": "business-and-data", "folder": "Ecommerce Alerts", "interval": "1m", "rules": [rule(*r) for r in BUSINESS]},
]}
out = pathlib.Path(__file__).resolve().parents[1] / "grafana/provisioning/alerting/alert-rules.yaml"
out.write_text("# GENERATED by observability/tools/generate_alert_rules.py -- edit the script, not this file.\n"
               + yaml.safe_dump(doc, sort_keys=False, width=200))
print("wrote", out, sum(len(g["rules"]) for g in doc["groups"]), "rules")
