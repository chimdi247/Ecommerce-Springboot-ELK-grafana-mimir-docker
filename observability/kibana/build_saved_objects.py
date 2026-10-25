#!/usr/bin/env python3
"""Generates saved-objects.ndjson (data-view-backed saved searches + a dashboard) for Kibana 8.x.
Run: python3 observability/kibana/build_saved_objects.py"""
import json, pathlib

DV = "ecommerce-logs"
SEARCHES = [
    ("all-logs", "Application logs (backend)", 'container_name : "ecommerce-backend" or service : "ecommerce-backend"',
     ["level", "logger_name", "message", "trace_id"]),
    ("errors", "Errors & warnings", 'level : "ERROR" or level : "WARN"',
     ["container_name", "level", "message", "trace_id"]),
    ("payments", "Payment events", 'event : payment_*',
     ["event", "orderId", "paymentIntentId", "message", "trace_id"]),
    ("emails", "E-mail notifications", 'event : email_*',
     ["event", "orderId", "recipient", "message"]),
    ("rate-limited", "Rate-limited requests (429)", 'event : "rate_limited"',
     ["client", "route", "limit", "retryAfterSeconds", "message"]),
    ("webhooks", "Stripe webhooks", 'event : webhook_*',
     ["event", "stripeType", "stripeEventId", "message"]),
    ("frontend", "nginx access log", 'container_name : "ecommerce-frontend"',
     ["method", "path", "code", "remote"]),
]


def search(id_, title, query, columns):
    source = {"query": {"query": query, "language": "kuery"}, "filter": [],
              "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index"}
    return {
        "type": "search", "id": id_,
        "attributes": {
            "title": title, "description": "", "columns": columns, "sort": [["@timestamp", "desc"]],
            "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps(source)},
        },
        "references": [{"id": DV, "name": "kibanaSavedObjectMeta.searchSourceJSON.index", "type": "index-pattern"}],
        "managed": False, "coreMigrationVersion": "8.8.0", "typeMigrationVersion": "8.0.0",
    }


def dashboard():
    panels, refs = [], []
    for i, (sid, *_rest) in enumerate(SEARCHES):
        panels.append({
            "version": "8.15.3", "type": "search",
            "gridData": {"x": 0 if i % 2 == 0 else 24, "y": (i // 2) * 15, "w": 24, "h": 15, "i": str(i + 1)},
            "panelIndex": str(i + 1), "embeddableConfig": {}, "panelRefName": f"panel_{i + 1}",
        })
        refs.append({"name": f"panel_{i + 1}", "type": "search", "id": sid})
    return {
        "type": "dashboard", "id": "ecommerce-logs-overview",
        "attributes": {
            "title": "Ecommerce - Logs overview", "description": "Application logs, errors, payment / e-mail events, rate limiting",
            "panelsJSON": json.dumps(panels),
            "optionsJSON": json.dumps({"useMargins": True, "syncColors": False, "syncCursor": True, "syncTooltips": False, "hidePanelTitles": False}),
            "timeRestore": True, "timeFrom": "now-1h", "timeTo": "now", "refreshInterval": {"pause": False, "value": 30000},
            "kibanaSavedObjectMeta": {"searchSourceJSON": json.dumps({"query": {"query": "", "language": "kuery"}, "filter": []})},
        },
        "references": refs, "managed": False, "coreMigrationVersion": "8.8.0", "typeMigrationVersion": "10.2.0",
    }


out = pathlib.Path(__file__).with_name("saved-objects.ndjson")
lines = [json.dumps(search(*s)) for s in SEARCHES] + [json.dumps(dashboard())]
out.write_text("\n".join(lines) + "\n")
print("wrote", out, len(lines), "objects")
