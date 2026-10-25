"""Tiny helper library to build Grafana dashboard JSON (schemaVersion 39)."""
PROM = {"type": "prometheus", "uid": "mimir"}
LOKI = {"type": "loki", "uid": "loki"}
TEMPO = {"type": "tempo", "uid": "tempo"}

GREEN, YELLOW, ORANGE, RED, BLUE = "green", "yellow", "orange", "red", "blue"


def steps(*pairs):
    """steps((GREEN,None),(YELLOW,30),(RED,40))"""
    return {"mode": "absolute", "steps": [{"color": c, "value": v} for c, v in pairs]}


class Dash:
    def __init__(self, uid, title, tags=None, refresh="15s", time_from="now-1h", description=""):
        self.uid, self.title, self.tags = uid, title, tags or []
        self.refresh, self.time_from, self.description = refresh, time_from, description
        self.panels, self.variables = [], []
        self._id = 0
        self._x = 0
        self._y = 0
        self._rowh = 0

    # ------------------------------------------------------------ layout
    def _place(self, w, h):
        if self._x + w > 24:
            self._x = 0
            self._y += self._rowh
            self._rowh = 0
        pos = {"x": self._x, "y": self._y, "w": w, "h": h}
        self._x += w
        self._rowh = max(self._rowh, h)
        return pos

    def row(self, title, collapsed=False):
        if self._x != 0:
            self._x = 0
            self._y += self._rowh
            self._rowh = 0
        self._id += 1
        self.panels.append({"id": self._id, "type": "row", "title": title, "collapsed": collapsed,
                            "gridPos": {"x": 0, "y": self._y, "w": 24, "h": 1}, "panels": []})
        self._y += 1
        self._rowh = 0

    def _add(self, panel, w, h):
        self._id += 1
        panel["id"] = self._id
        panel["gridPos"] = self._place(w, h)
        self.panels.append(panel)

    # ------------------------------------------------------------ targets
    @staticmethod
    def _targets(items, ds, instant=False, table=False):
        out = []
        for i, item in enumerate(items):
            expr, legend = item if isinstance(item, tuple) else (item, "__auto")
            t = {"refId": chr(65 + i), "datasource": ds, "expr": expr, "legendFormat": legend,
                 "editorMode": "code", "instant": instant, "range": not instant}
            if table:
                t["format"] = "table"
            out.append(t)
        return out

    # ------------------------------------------------------------ panels
    def stat(self, title, items, w=4, h=4, unit="short", thresholds=None, decimals=None,
             description="", mappings=None, graph=True, color_mode="value", instant=False):
        fc = {"unit": unit, "thresholds": thresholds or steps((GREEN, None)),
              "color": {"mode": "thresholds"}, "mappings": mappings or []}
        if decimals is not None:
            fc["decimals"] = decimals
        self._add({"type": "stat", "title": title, "description": description, "datasource": PROM,
                   "targets": self._targets(items, PROM, instant=instant),
                   "fieldConfig": {"defaults": fc, "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                               "colorMode": color_mode, "graphMode": "area" if graph else "none",
                               "textMode": "auto", "justifyMode": "auto", "orientation": "auto"}}, w, h)

    def gauge(self, title, items, w=4, h=5, unit="percent", minv=0, maxv=100, thresholds=None, description=""):
        self._add({"type": "gauge", "title": title, "description": description, "datasource": PROM,
                   "targets": self._targets(items, PROM, instant=True),
                   "fieldConfig": {"defaults": {"unit": unit, "min": minv, "max": maxv,
                                                "thresholds": thresholds or steps((GREEN, None), (YELLOW, 30), (RED, 40)),
                                                "color": {"mode": "thresholds"}}, "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                               "showThresholdMarkers": True, "showThresholdLabels": False}}, w, h)

    def ts(self, title, items, w=12, h=8, unit="short", stack=False, description="", thresholds=None,
           minv=None, maxv=None, fill=10, ds=None, legend_calcs=None, threshold_style=None, overrides=None):
        ds = ds or PROM
        custom = {"drawStyle": "line", "lineInterpolation": "smooth", "lineWidth": 1, "fillOpacity": fill,
                  "showPoints": "never", "spanNulls": True,
                  "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
        if threshold_style:
            custom["thresholdsStyle"] = {"mode": threshold_style}
        fc = {"unit": unit, "custom": custom,
              "thresholds": thresholds or steps((GREEN, None)), "color": {"mode": "palette-classic"}}
        if minv is not None:
            fc["min"] = minv
        if maxv is not None:
            fc["max"] = maxv
        self._add({"type": "timeseries", "title": title, "description": description, "datasource": ds,
                   "targets": self._targets(items, ds),
                   "fieldConfig": {"defaults": fc, "overrides": overrides or []},
                   "options": {"legend": {"displayMode": "table" if legend_calcs else "list", "placement": "bottom",
                                          "calcs": legend_calcs or []},
                               "tooltip": {"mode": "multi", "sort": "desc"}}}, w, h)

    def table(self, title, items, w=12, h=8, unit="short", description="", transformations=None,
              overrides=None, sort_by=None):
        opts = {"showHeader": True, "cellHeight": "sm"}
        if sort_by:
            opts["sortBy"] = [{"displayName": sort_by, "desc": True}]
        self._add({"type": "table", "title": title, "description": description, "datasource": PROM,
                   "targets": self._targets(items, PROM, instant=True, table=True),
                   "fieldConfig": {"defaults": {"unit": unit, "custom": {"align": "auto"}}, "overrides": overrides or []},
                   "transformations": transformations or [], "options": opts}, w, h)

    def logs(self, title, expr, w=24, h=10, description=""):
        self._add({"type": "logs", "title": title, "description": description, "datasource": LOKI,
                   "targets": [{"refId": "A", "datasource": LOKI, "expr": expr, "queryType": "range"}],
                   "options": {"showTime": True, "showLabels": False, "showCommonLabels": False,
                               "wrapLogMessage": True, "prettifyLogMessage": False, "enableLogDetails": True,
                               "dedupStrategy": "none", "sortOrder": "Descending"}}, w, h)

    def traces(self, title, query, w=12, h=8, description="", limit=20):
        self._add({"type": "table", "title": title, "description": description, "datasource": TEMPO,
                   "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "traceql", "query": query,
                                "limit": limit, "tableType": "traces"}],
                   "fieldConfig": {"defaults": {}, "overrides": []}, "options": {"showHeader": True}}, w, h)

    def nodegraph(self, title, w=12, h=10, description=""):
        self._add({"type": "nodeGraph", "title": title, "description": description, "datasource": TEMPO,
                   "targets": [{"refId": "A", "datasource": TEMPO, "queryType": "serviceMap"}],
                   "options": {}}, w, h)

    def text(self, title, content, w=24, h=4):
        self._add({"type": "text", "title": title, "options": {"mode": "markdown", "content": content}}, w, h)

    # ------------------------------------------------------------ variables
    def var_query(self, name, query, label=None, multi=True, include_all=True, ds=None):
        self.variables.append({
            "type": "query", "name": name, "label": label or name, "datasource": ds or PROM,
            "query": {"query": query, "refId": f"var-{name}"}, "definition": query, "refresh": 2,
            "multi": multi, "includeAll": include_all, "allValue": ".*", "sort": 1,
            "current": {"selected": True, "text": "All" if include_all else "", "value": "$__all" if include_all else ""},
            "options": [], "regex": ""})

    def var_custom(self, name, values, default, label=None):
        self.variables.append({
            "type": "custom", "name": name, "label": label or name, "query": values,
            "current": {"selected": True, "text": default, "value": default},
            "options": [{"selected": v == default, "text": v, "value": v} for v in values.split(",")],
            "multi": False, "includeAll": False})

    def var_text(self, name, default, label=None):
        self.variables.append({"type": "textbox", "name": name, "label": label or name, "query": default,
                               "current": {"selected": False, "text": default, "value": default}, "options": []})

    # ------------------------------------------------------------ output
    def build(self):
        return {"uid": self.uid, "title": self.title, "tags": ["ecommerce"] + self.tags, "description": self.description,
                "timezone": "browser", "editable": True, "graphTooltip": 1, "schemaVersion": 39, "version": 1,
                "refresh": self.refresh, "time": {"from": self.time_from, "to": "now"},
                "templating": {"list": self.variables},
                "annotations": {"list": [{"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"},
                                          "enable": True, "hide": True, "iconColor": "rgba(0, 211, 255, 1)",
                                          "name": "Annotations & Alerts", "type": "dashboard"}]},
                "links": [{"title": "Ecommerce dashboards", "type": "dashboards", "tags": ["ecommerce"],
                           "asDropdown": True, "includeVars": False, "keepTime": True}],
                "panels": self.panels}
