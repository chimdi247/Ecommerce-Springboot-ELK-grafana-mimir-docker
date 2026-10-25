#!/usr/bin/env python3
"""Generates grafana/dashboards/*.json.  Run: python3 observability/tools/generate_dashboards.py
(They are normal provisioned dashboards; you may also edit them in the Grafana UI.)

Metric names come from: OpenTelemetry Java agent (http_server_request_duration_*, jvm_*), our manual
instrumentation (ecommerce_*), cAdvisor, node-exporter, mysqld-exporter, redis_exporter, blackbox
exporter and Fluent Bit. The collector keeps OTel names as-is (add_metric_suffixes: false)."""
import json
import pathlib

from dashlib import *  # noqa: F401,F403

OUT = pathlib.Path(__file__).resolve().parents[1] / "grafana/dashboards"
OUT.mkdir(parents=True, exist_ok=True)
KIBANA_LINK = {"title": "Kibana logs", "type": "link", "url": "http://localhost:5601/app/dashboards#/view/ecommerce-logs-overview",
               "icon": "doc", "targetBlank": True, "tooltip": "Structured logs (Elasticsearch / Kibana)"}


def save(d):
    built = d.build()
    built["links"].append(KIBANA_LINK)
    (OUT / f"{d.uid}.json").write_text(json.dumps(built, indent=2) + "\n")
    print("wrote", d.uid, f"({len([p for p in d.panels if p['type'] != 'row'])} panels)")


J = 'job="ecommerce-backend"'
DUR = "http_server_request_duration"
REQ_ALL = f'{DUR}_count{{{J}}}'
REQ_5XX = f'{DUR}_count{{{J},http_response_status_code=~"5.."}}'
BUCKET = f'{DUR}_bucket{{{J}}}'
RI = "$__rate_interval"
OK_UP = [{"type": "value", "options": {"1": {"text": "UP", "color": "green"}, "0": {"text": "DOWN", "color": "red"}}}]
thr40 = steps((GREEN, None), (YELLOW, 30), (RED, 40))
CT = 'name=~"ecommerce-.+"'

# =============================================================================== 1. Overview
d = Dash("ecommerce-overview", "Ecommerce - Overview (SLI / SLO / KPI)", ["overview", "slo"],
         description="Uptime, availability and latency SLOs, error budget, successful vs failed requests and business KPIs.")
d.var_text("slo_availability", "99.5", "Availability SLO (%)")
d.var_custom("slo_latency", "0.1,0.25,0.5,1,2.5", "0.5", "Latency SLO threshold (s)")

d.row("Service health & SLOs")
d.stat("Services UP", ['100 * sum(probe_success{job="blackbox-http"}) / count(probe_success{job="blackbox-http"})'], unit="percent",
       thresholds=steps((RED, None), (YELLOW, 99), (GREEN, 100)), decimals=0, description="Health probes: backend /actuator/health and the frontend.")
d.stat("Uptime (selected range)", ['100 * avg(avg_over_time(probe_success{job="blackbox-http"}[$__range]))'], unit="percent",
       thresholds=steps((RED, None), (YELLOW, 99), (GREEN, 99.9)), decimals=3, description="Uptime SLI from synthetic probes.")
d.stat("Availability SLI", [f'100 * (1 - (sum(increase({REQ_5XX}[$__range])) or vector(0)) / sum(increase({REQ_ALL}[$__range])))'], unit="percent",
       thresholds=steps((RED, None), (YELLOW, 99), (GREEN, 99.5)), decimals=3, description="Share of requests that did not fail with 5xx.")
d.stat("Error budget remaining", [f'clamp_min(100 * (1 - ((sum(increase({REQ_5XX}[$__range])) or vector(0)) / sum(increase({REQ_ALL}[$__range]))) / ((100 - $slo_availability) / 100)), 0)'],
       unit="percent", thresholds=steps((RED, None), (YELLOW, 25), (GREEN, 50)), decimals=1, description="100% = no errors; 0% = SLO breached for the selected range.")
d.stat("Latency SLO attainment", [f'100 * sum(increase({DUR}_bucket{{{J},le="$slo_latency"}}[$__range])) / sum(increase({DUR}_count{{{J}}}[$__range]))'],
       unit="percent", thresholds=steps((RED, None), (YELLOW, 95), (GREEN, 99)), decimals=2, description="Share of requests faster than the SLO latency threshold.")
d.stat("Requests / sec", [f'sum(rate({REQ_ALL}[{RI}]))'], unit="reqps", decimals=2)

d.row("Successful vs failed HTTP requests")
d.stat("Successful (2xx/3xx)", [f'sum(increase({DUR}_count{{{J},http_response_status_code=~"[23].."}}[$__range])) or vector(0)'], unit="short", thresholds=steps((GREEN, None)), graph=False, decimals=0)
d.stat("Client errors (4xx)", [f'sum(increase({DUR}_count{{{J},http_response_status_code=~"4.."}}[$__range])) or vector(0)'], unit="short", thresholds=steps((BLUE, None)), graph=False, decimals=0)
d.stat("Failed (5xx)", [f'sum(increase({REQ_5XX}[$__range])) or vector(0)'], unit="short", thresholds=steps((GREEN, None), (RED, 1)), graph=False, decimals=0)
d.ts("Requests by status class", [
    (f'sum(rate({DUR}_count{{{J},http_response_status_code=~"2.."}}[{RI}]))', "2xx"),
    (f'sum(rate({DUR}_count{{{J},http_response_status_code=~"3.."}}[{RI}]))', "3xx"),
    (f'sum(rate({DUR}_count{{{J},http_response_status_code=~"4.."}}[{RI}]))', "4xx"),
    (f'sum(rate({REQ_5XX}[{RI}]))', "5xx")], w=12, unit="reqps", stack=True,
    overrides=[{"matcher": {"id": "byName", "options": n}, "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}
               for n, c in [("5xx", "red"), ("4xx", "orange"), ("2xx", "green")]])

d.row("RED: rate, errors, duration")
d.ts("Error rate (5xx %)", [(f'100 * sum(rate({REQ_5XX}[{RI}])) / sum(rate({REQ_ALL}[{RI}]))', "5xx %")], unit="percent", thresholds=steps((GREEN, None), (RED, 5)), threshold_style="line")
d.ts("Latency percentiles", [
    (f'histogram_quantile(0.50, sum by (le) (rate({BUCKET}[{RI}])))', "p50"),
    (f'histogram_quantile(0.95, sum by (le) (rate({BUCKET}[{RI}])))', "p95"),
    (f'histogram_quantile(0.99, sum by (le) (rate({BUCKET}[{RI}])))', "p99")], unit="s", thresholds=steps((GREEN, None), (RED, 1)), threshold_style="line")
d.ts("Request rate by route", [(f'sum by (http_request_method, http_route) (rate({REQ_ALL}[{RI}]))', "{{http_request_method}} {{http_route}}")], unit="reqps")
d.ts("p95 latency by route", [(f'histogram_quantile(0.95, sum by (le, http_route) (rate({BUCKET}[{RI}])))', "{{http_route}}")], unit="s")
d.table("Slowest routes (p95)", [f'topk(10, histogram_quantile(0.95, sum by (le, http_request_method, http_route) (rate({BUCKET}[$__range]))))'],
        unit="s", sort_by="Value", transformations=[{"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {"Value": "p95"}}}])
d.table("Routes with most 5xx", [f'topk(10, sum by (http_request_method, http_route) (increase({REQ_5XX}[$__range])) > 0)'],
        unit="short", sort_by="Value", transformations=[{"id": "organize", "options": {"excludeByName": {"Time": True}, "renameByName": {"Value": "5xx count"}}}])

d.row("Business KPIs")
d.stat("Orders placed", ['sum(increase(ecommerce_orders_placed[$__range])) or vector(0)'], w=4, graph=False, decimals=0, thresholds=steps((BLUE, None)))
d.stat("Payments succeeded", ['sum(increase(ecommerce_payments{outcome="succeeded"}[$__range])) or vector(0)'], w=4, graph=False, decimals=0, thresholds=steps((GREEN, None)))
d.stat("Revenue", ['sum(increase(ecommerce_revenue[$__range])) or vector(0)'], w=4, graph=False, decimals=2, unit="currencyUSD", thresholds=steps((BLUE, None)))
d.stat("Payment success rate", ['100 * sum(increase(ecommerce_payments{outcome="succeeded"}[$__range])) / sum(increase(ecommerce_payments{outcome=~"succeeded|failed"}[$__range]))'],
       w=4, unit="percent", decimals=1, thresholds=steps((RED, None), (YELLOW, 80), (GREEN, 95)))
d.stat("Product cache hit ratio", ['100 * sum(increase(ecommerce_cache_requests{result="hit"}[$__range])) / sum(increase(ecommerce_cache_requests{result=~"hit|miss"}[$__range]))'],
       w=4, unit="percent", decimals=1, thresholds=steps((RED, None), (YELLOW, 50), (GREEN, 80)))
d.stat("Rate-limited (429)", ['sum(increase(ecommerce_ratelimit_rejected[$__range])) or vector(0)'], w=4, graph=False, decimals=0, thresholds=steps((GREEN, None), (YELLOW, 1)))
d.ts("Orders & payments per minute", [('60 * sum(rate(ecommerce_orders_placed[$__rate_interval]))', "orders placed"),
                                      ('60 * sum(rate(ecommerce_payments{outcome="succeeded"}[$__rate_interval]))', "payments succeeded"),
                                      ('60 * sum(rate(ecommerce_payments{outcome="failed"}[$__rate_interval]))', "payments failed")], w=12, unit="short")
d.ts("Registrations & logins per minute", [('60 * sum(rate(ecommerce_users_registered[$__rate_interval]))', "registrations"),
                                           ('60 * sum by (outcome) (rate(ecommerce_logins[$__rate_interval]))', "login {{outcome}}")], w=12, unit="short")
save(d)

# =============================================================================== 2. Payments & business
d = Dash("ecommerce-payments", "Ecommerce - Payments, idempotency & notifications", ["payments", "business", "stripe"],
         description="Payment funnel, idempotency protection (replays/blocked double payments), revenue, confirmation e-mails and cache/rate-limit behaviour.")
d.row("Payment funnel")
d.stat("Orders placed", ['sum(increase(ecommerce_orders_placed[$__range])) or vector(0)'], w=3, graph=False, decimals=0, thresholds=steps((BLUE, None)))
d.stat("Payments initiated", ['sum(increase(ecommerce_payments{outcome="initiated"}[$__range])) or vector(0)'], w=3, graph=False, decimals=0, thresholds=steps((BLUE, None)))
d.stat("Succeeded", ['sum(increase(ecommerce_payments{outcome="succeeded"}[$__range])) or vector(0)'], w=3, graph=False, decimals=0, thresholds=steps((GREEN, None)))
d.stat("Failed", ['sum(increase(ecommerce_payments{outcome="failed"}[$__range])) or vector(0)'], w=3, graph=False, decimals=0, thresholds=steps((GREEN, None), (RED, 1)))
d.stat("Checkout conversion", ['100 * sum(increase(ecommerce_payments{outcome="succeeded"}[$__range])) / sum(increase(ecommerce_orders_placed[$__range]))'], w=6, unit="percent", decimals=1,
       thresholds=steps((RED, None), (YELLOW, 30), (GREEN, 60)), description="Paid orders / orders placed.")
d.stat("Revenue", ['sum(increase(ecommerce_revenue[$__range])) or vector(0)'], w=6, graph=False, decimals=2, unit="currencyUSD", thresholds=steps((BLUE, None)))
d.ts("Payment outcomes per minute", [('60 * sum by (outcome) (rate(ecommerce_payments[$__rate_interval]))', "{{outcome}}")], w=12, stack=True)
d.ts("Revenue per hour", [('3600 * sum(rate(ecommerce_revenue[$__rate_interval]))', "revenue / h")], w=12, unit="currencyUSD")

d.row("Idempotency: nobody pays twice")
d.stat("Replayed requests (same key)", ['sum(increase(ecommerce_payments{outcome="replayed"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((BLUE, None)),
       description="A retried request with the same Idempotency-Key got the original PaymentIntent back.")
d.stat("Reused open intents (new key)", ['sum(increase(ecommerce_payments{outcome="reused"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((BLUE, None)),
       description="Another key for an order that already had an open PaymentIntent: the existing one was returned.")
d.stat("Blocked: already paid", ['sum(increase(ecommerce_payments{outcome="already_paid_blocked"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((GREEN, None), (YELLOW, 1)),
       description="Attempts to pay an order that was already PAID (HTTP 409).")
d.stat("Duplicate-payment protection events", ['sum(increase(ecommerce_payments{outcome=~"replayed|reused|already_paid_blocked"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((BLUE, None)))
d.ts("Idempotency events per minute", [('60 * sum by (outcome) (rate(ecommerce_payments{outcome=~"replayed|reused|already_paid_blocked"}[$__rate_interval]))', "{{outcome}}")], w=24)

d.row("Confirmation e-mails (Gmail SMTP)")
d.stat("E-mails sent", ['sum(increase(ecommerce_notifications{outcome="sent"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((GREEN, None)))
d.stat("E-mails failed", ['sum(increase(ecommerce_notifications{outcome="failed"}[$__range])) or vector(0)'], w=6, graph=False, decimals=0, thresholds=steps((GREEN, None), (RED, 1)))
d.ts("Notifications by outcome", [('60 * sum by (channel, outcome) (rate(ecommerce_notifications[$__rate_interval]))', "{{channel}} {{outcome}}")], w=12)

d.row("Cache & rate limiting (GET /product/get-all)")
d.gauge("Product cache hit ratio", ['100 * sum(increase(ecommerce_cache_requests{result="hit"}[$__range])) / sum(increase(ecommerce_cache_requests{result=~"hit|miss"}[$__range]))'], w=4, thresholds=steps((RED, None), (YELLOW, 50), (GREEN, 80)))
d.ts("Cache lookups by result", [('60 * sum by (result) (rate(ecommerce_cache_requests[$__rate_interval]))', "{{result}}")], w=10, description="hit = served from Redis, miss = loaded from MySQL, error = Redis unavailable (fell back to MySQL).")
d.ts("Rate-limited requests (429) per minute", [('60 * sum(rate(ecommerce_ratelimit_rejected[$__rate_interval]))', "429 / min"),
                                                (f'60 * sum(rate({DUR}_count{{{J},http_route="/product/get-all"}}[{RI}]))', "get-all / min")], w=10)
save(d)

# =============================================================================== 3. JVM
d = Dash("ecommerce-jvm", "Ecommerce - Backend JVM & runtime", ["jvm", "backend"], description="OpenTelemetry Java agent runtime metrics for the Spring Boot backend.")
d.row("Memory & CPU")
d.gauge("Heap used % of max", [f'100 * max(jvm_memory_used{{{J},jvm_memory_type="heap"}}) / max(jvm_memory_limit{{{J},jvm_memory_type="heap"}})'], w=6, thresholds=steps((GREEN, None), (YELLOW, 70), (RED, 85)))
d.stat("JVM CPU utilisation", [f'100 * max(jvm_cpu_recent_utilization{{{J}}})'], w=6, unit="percent", decimals=1, thresholds=thr40)
d.stat("Live threads", [f'max(jvm_thread_count{{{J}}})'], w=6, decimals=0)
d.stat("Loaded classes", [f'max(jvm_class_count{{{J}}})'], w=6, decimals=0)
d.ts("Heap vs non-heap", [(f'sum by (jvm_memory_type) (jvm_memory_used{{{J}}})', "{{jvm_memory_type}} used"), (f'max(jvm_memory_limit{{{J},jvm_memory_type="heap"}})', "heap max")], unit="bytes")
d.ts("JVM CPU", [(f'jvm_cpu_recent_utilization{{{J}}}', "recent utilisation")], unit="percentunit", thresholds=steps((GREEN, None), (RED, 0.4)), threshold_style="line")
d.ts("Memory pools", [(f'sum by (jvm_memory_pool_name) (jvm_memory_used{{{J}}})', "{{jvm_memory_pool_name}}")], unit="bytes")
d.ts("Threads by state", [(f'sum by (jvm_thread_state) (jvm_thread_count{{{J}}})', "{{jvm_thread_state}}")])
d.row("Garbage collection")
d.ts("GC time (fraction of wall clock)", [(f'sum by (jvm_gc_name) (rate(jvm_gc_duration_sum{{{J}}}[{RI}]))', "{{jvm_gc_name}}")], unit="percentunit")
d.ts("GC pauses per minute", [(f'60 * sum by (jvm_gc_name) (rate(jvm_gc_duration_count{{{J}}}[{RI}]))', "{{jvm_gc_name}}")])
d.row("HTTP server")
d.ts("In-flight requests", [(f'sum(http_server_active_requests{{{J}}})', "active")])
d.ts("Throughput", [(f'sum(rate({REQ_ALL}[{RI}]))', "req/s")], unit="reqps")
save(d)

# =============================================================================== 4. Infrastructure
d = Dash("ecommerce-infrastructure", "Ecommerce - Infrastructure (nodes & containers)", ["infra", "cpu", "memory"],
         description="Host and per-container CPU/memory/network. Alerts fire when CPU or memory exceeds 40%.")
d.var_query("container", f'label_values(container_last_seen{{{CT}}}, name)', "Container")
Cn = 'name=~"$container"'
d.row("Node (host)")
d.gauge("Node CPU %", ['100 - avg(rate(node_cpu_seconds_total{mode="idle"}[2m])) * 100'], thresholds=thr40, description="Alert threshold: 40%")
d.gauge("Node memory %", ['100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)'], thresholds=thr40, description="Alert threshold: 40%")
d.gauge("Root disk used %", ['100 * (1 - node_filesystem_avail_bytes{mountpoint="/",fstype!="rootfs"} / node_filesystem_size_bytes{mountpoint="/",fstype!="rootfs"})'], thresholds=steps((GREEN, None), (YELLOW, 70), (RED, 85)))
d.stat("Load (1m)", ['node_load1'], w=3, decimals=2)
d.stat("Node uptime", ['time() - node_boot_time_seconds'], w=5, unit="s")
d.ts("Node CPU by mode", [('100 * avg by (mode) (rate(node_cpu_seconds_total[2m]))', "{{mode}}")], unit="percent", stack=True, maxv=100)
d.ts("Node memory", [('node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes', "used"), ('node_memory_MemAvailable_bytes', "available")], unit="bytes", stack=True)
d.ts("Node network (bytes/s)", [('sum(rate(node_network_receive_bytes_total{device!~"lo|veth.*|docker.*|br-.*"}[2m]))', "rx"), ('sum(rate(node_network_transmit_bytes_total{device!~"lo|veth.*|docker.*|br-.*"}[2m]))', "tx")], unit="Bps")
d.ts("Node disk I/O (bytes/s)", [('sum(rate(node_disk_read_bytes_total[2m]))', "read"), ('sum(rate(node_disk_written_bytes_total[2m]))', "write")], unit="Bps")
d.row("Containers - CPU")
d.ts("Container CPU % of its limit (alert > 40%)", [(f'100 * sum by (name) (rate(container_cpu_usage_seconds_total{{{Cn}}}[{RI}])) / max by (name) (container_spec_cpu_quota{{{Cn}}} / container_spec_cpu_period{{{Cn}}} > 0)', "{{name}}")],
     unit="percent", thresholds=thr40, threshold_style="line", description="Only containers with a CPU limit (docker-compose deploy.resources.limits).")
d.ts("Container CPU (cores used)", [(f'sum by (name) (rate(container_cpu_usage_seconds_total{{{Cn}}}[{RI}]))', "{{name}}")])
d.row("Containers - memory")
d.ts("Container memory % of its limit (alert > 40%)", [(f'100 * max by (name) (container_memory_working_set_bytes{{{Cn}}}) / max by (name) (container_spec_memory_limit_bytes{{{Cn}}} > 0)', "{{name}}")],
     unit="percent", thresholds=thr40, threshold_style="line")
d.ts("Container memory working set", [(f'max by (name) (container_memory_working_set_bytes{{{Cn}}})', "{{name}}")], unit="bytes")
d.row("Containers - network, restarts")
d.ts("Network receive (bytes/s)", [(f'sum by (name) (rate(container_network_receive_bytes_total{{{Cn}}}[{RI}]))', "{{name}}")], w=8, unit="Bps")
d.ts("Network transmit (bytes/s)", [(f'sum by (name) (rate(container_network_transmit_bytes_total{{{Cn}}}[{RI}]))', "{{name}}")], w=8, unit="Bps")
d.ts("Restarts / OOM kills (1h)", [(f'changes(container_start_time_seconds{{{Cn}}}[1h])', "restarts {{name}}"), (f'increase(container_oom_events_total{{{Cn}}}[1h])', "oom {{name}}")], w=8)
d.table("Container summary", [f'max by (name) (time() - container_start_time_seconds{{{Cn}}})',
    f'100 * max by (name) (container_memory_working_set_bytes{{{Cn}}}) / max by (name) (container_spec_memory_limit_bytes{{{Cn}}} > 0)',
    f'100 * sum by (name) (rate(container_cpu_usage_seconds_total{{{Cn}}}[2m])) / max by (name) (container_spec_cpu_quota{{{Cn}}} / container_spec_cpu_period{{{Cn}}} > 0)'],
    w=24, h=9, transformations=[{"id": "joinByField", "options": {"byField": "name", "mode": "outer"}},
                                {"id": "organize", "options": {"excludeByName": {"Time 1": True, "Time 2": True, "Time 3": True},
                                                                "renameByName": {"Value #A": "Uptime (s)", "Value #B": "Mem % of limit", "Value #C": "CPU % of limit"}}}])
d.row("Telemetry & log pipeline health")
d.stat("Targets down", ['count(up == 0) or vector(0)'], w=4, decimals=0, thresholds=steps((GREEN, None), (RED, 1)), graph=False)
d.ts("Fluent Bit: records in / out per second", [('sum(rate(fluentbit_input_records_total[2m]))', "input"), ('sum(rate(fluentbit_output_proc_records_total[2m]))', "output (to Elasticsearch)")], w=10, unit="ops")
d.ts("Fluent Bit: errors & retries", [('sum(rate(fluentbit_output_errors_total[2m]))', "errors"), ('sum(rate(fluentbit_output_retries_total[2m]))', "retries"), ('sum(rate(fluentbit_output_dropped_records_total[2m]))', "dropped")], w=10, unit="ops")
d.ts("OTel collector: accepted metric points/s", [('sum(rate({__name__=~"otelcol_receiver_accepted_metric_points.*"}[2m]))', "accepted"),
                                                  ('sum(rate({__name__=~"otelcol_exporter_send_failed_metric_points.*"}[2m]))', "failed to export")], w=24, unit="ops")
save(d)

# =============================================================================== 5. Datastores
d = Dash("ecommerce-datastores", "Ecommerce - MySQL & Redis", ["mysql", "redis", "cache"], description="Datastore health and the Redis cache-aside behaviour.")
d.row("MySQL")
d.stat("Threads connected", ['mysql_global_status_threads_connected'], w=4, decimals=0)
d.stat("Threads running", ['mysql_global_status_threads_running'], w=4, decimals=0)
d.gauge("% of max_connections", ['100 * mysql_global_status_threads_connected / mysql_global_variables_max_connections'], w=4, thresholds=steps((GREEN, None), (YELLOW, 60), (RED, 80)))
d.stat("Queries / sec", ['rate(mysql_global_status_queries[$__rate_interval])'], w=4, unit="ops", decimals=1)
d.stat("Slow queries / min", ['60 * rate(mysql_global_status_slow_queries[$__rate_interval])'], w=4, decimals=2)
d.stat("Aborted connects / min", ['60 * rate(mysql_global_status_aborted_connects[$__rate_interval])'], w=4, decimals=2, thresholds=steps((GREEN, None), (RED, 1)))
d.ts("Connections", [('mysql_global_status_threads_connected', "connected"), ('mysql_global_status_threads_running', "running"), ('mysql_global_variables_max_connections', "max")])
d.ts("Commands / sec", [('sum by (command) (rate(mysql_global_status_commands_total{command=~"select|insert|update|delete|commit|rollback"}[$__rate_interval]))', "{{command}}")], unit="ops")
d.ts("InnoDB buffer pool hit ratio", [('100 * (1 - rate(mysql_global_status_innodb_buffer_pool_reads[$__rate_interval]) / rate(mysql_global_status_innodb_buffer_pool_read_requests[$__rate_interval]))', "hit %")], unit="percent")
d.ts("Network traffic", [('rate(mysql_global_status_bytes_received[$__rate_interval])', "received"), ('rate(mysql_global_status_bytes_sent[$__rate_interval])', "sent")], unit="Bps")
d.row("Redis")
d.stat("Connected clients", ['redis_connected_clients'], w=4, decimals=0)
d.gauge("Server hit ratio %", ['100 * rate(redis_keyspace_hits_total[$__rate_interval]) / (rate(redis_keyspace_hits_total[$__rate_interval]) + rate(redis_keyspace_misses_total[$__rate_interval]))'], w=4, thresholds=steps((RED, None), (YELLOW, 50), (GREEN, 80)))
d.stat("Commands / sec", ['rate(redis_commands_processed_total[$__rate_interval])'], w=4, unit="ops", decimals=1)
d.gauge("Memory usage %", ['100 * redis_memory_used_bytes / redis_memory_max_bytes'], w=4, thresholds=steps((GREEN, None), (YELLOW, 60), (RED, 80)), description="Relative to maxmemory (128mb in docker-compose).")
d.stat("Cached keys", ['sum(redis_db_keys)'], w=4, decimals=0)
d.stat("Evicted keys / min", ['60 * rate(redis_evicted_keys_total[$__rate_interval])'], w=4, decimals=2, thresholds=steps((GREEN, None), (YELLOW, 1)))
d.ts("Cache hit ratio (%)", [('100 * rate(redis_keyspace_hits_total[$__rate_interval]) / (rate(redis_keyspace_hits_total[$__rate_interval]) + rate(redis_keyspace_misses_total[$__rate_interval]))', "Redis server"),
                             ('100 * sum(rate(ecommerce_cache_requests{result="hit"}[$__rate_interval])) / sum(rate(ecommerce_cache_requests{result=~"hit|miss"}[$__rate_interval]))', "application (product cache)")], unit="percent", maxv=100)
d.ts("Command throughput", [('rate(redis_commands_processed_total[$__rate_interval])', "ops/s")], unit="ops")
d.ts("Memory", [('redis_memory_used_bytes', "used"), ('redis_memory_max_bytes', "maxmemory")], unit="bytes")
d.ts("Top commands / sec", [('topk(8, sum by (cmd) (rate(redis_commands_total[$__rate_interval])))', "{{cmd}}")], unit="ops")
save(d)

# =============================================================================== 6. k6
d = Dash("ecommerce-k6", "Ecommerce - k6 load tests", ["k6", "performance"], refresh="5s", time_from="now-30m",
         description="Live results while running tests/k6 with the Prometheus remote-write output (see tests/README.md).")
d.var_query("testid", "label_values(k6_vus, testid)", "Test run", multi=False, include_all=True)
T = 'testid=~"$testid"'
d.stat("Virtual users", [f'max(k6_vus{{{T}}})'], w=4, decimals=0)
d.stat("Requests / sec", [f'sum(rate(k6_http_reqs_total{{{T}}}[{RI}]))'], w=4, unit="reqps", decimals=1)
d.stat("Failed requests %", [f'100 * avg(k6_http_req_failed_rate{{{T}}})'], w=4, unit="percent", decimals=2, thresholds=steps((GREEN, None), (YELLOW, 1), (RED, 5)))
d.stat("p95 latency", [f'max(k6_http_req_duration_p95{{{T}}})'], w=4, unit="s", thresholds=steps((GREEN, None), (YELLOW, 0.5), (RED, 1)))
d.stat("Checks passed %", [f'100 * avg(k6_checks_rate{{{T}}})'], w=4, unit="percent", decimals=1, thresholds=steps((RED, None), (YELLOW, 95), (GREEN, 99)))
d.stat("Iterations / sec", [f'sum(rate(k6_iterations_total{{{T}}}[{RI}]))'], w=4, unit="ops", decimals=1)
d.ts("VUs vs request rate", [(f'max(k6_vus{{{T}}})', "VUs"), (f'sum(rate(k6_http_reqs_total{{{T}}}[{RI}]))', "req/s")])
d.ts("Client-side latency (k6)", [(f'max(k6_http_req_duration_p95{{{T}}})', "p95"), (f'max(k6_http_req_duration_p99{{{T}}})', "p99"), (f'max(k6_http_req_duration_avg{{{T}}})', "avg")], unit="s")
d.ts("Server-side p95 during the test", [(f'histogram_quantile(0.95, sum by (le) (rate({BUCKET}[{RI}])))', "backend p95")], unit="s")
d.ts("Server-side 5xx % and 429/min", [(f'100 * sum(rate({REQ_5XX}[{RI}])) / sum(rate({REQ_ALL}[{RI}]))', "5xx %"), ('60 * sum(rate(ecommerce_ratelimit_rejected[$__rate_interval]))', "429 per min")])
d.ts("Container CPU % of limit", [(f'100 * sum by (name) (rate(container_cpu_usage_seconds_total{{{CT}}}[2m])) / max by (name) (container_spec_cpu_quota{{{CT}}} / container_spec_cpu_period{{{CT}}} > 0)', "{{name}}")],
     unit="percent", thresholds=steps((GREEN, None), (RED, 40)), threshold_style="line")
d.ts("Container memory % of limit", [(f'100 * max by (name) (container_memory_working_set_bytes{{{CT}}}) / max by (name) (container_spec_memory_limit_bytes{{{CT}}} > 0)', "{{name}}")],
     unit="percent", thresholds=steps((GREEN, None), (RED, 40)), threshold_style="line")
save(d)
