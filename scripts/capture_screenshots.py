#!/usr/bin/env python3
"""Captures the README screenshots from a running instance after scripts/kimi_agent_demo.py has run.

Requires: pip install playwright mcp && playwright install chromium   (or pass --chrome for an installed Chrome)
Usage:    python scripts/capture_screenshots.py [--base-url http://localhost:8080] [--key <write key>] [--chrome]
Shots:    transcript (docs/demo/transcript.json as a chat), tools (live tools/resources/prompts list over MCP),
          audit (approval log + tool audit from /api/bridge), swagger
"""
import argparse
import asyncio
import html
import json
import re
import urllib.request
from pathlib import Path

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared._httpx_utils import create_mcp_http_client
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "screenshots"

CSS = """
body{margin:0;background:#e9edf2;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;padding:26px;color:#1f2328}
.win{background:#fff;border-radius:12px;box-shadow:0 8px 30px rgba(0,0,0,.18);overflow:hidden;max-width:1180px}
.bar{background:#f6f8fa;padding:11px 16px;display:flex;align-items:center;gap:8px;border-bottom:1px solid #d0d7de}
.dot{width:12px;height:12px;border-radius:50%} .t{color:#57606a;font-size:13px;margin-left:12px}
.body{padding:18px 22px 22px}
h2{font-size:17px;margin:4px 0 12px} .sub{color:#57606a;font-size:13px;margin:-6px 0 14px}
.msg{display:flex;margin:10px 0} .msg.user{justify-content:flex-end}
.bubble{max-width:78%;padding:10px 14px;border-radius:14px;font-size:14px;line-height:1.5}
.user .bubble{background:#0969da;color:#fff;border-bottom-right-radius:4px}
.assistant .bubble{background:#f6f8fa;border:1px solid #d0d7de;border-bottom-left-radius:4px}
.who{font-size:11px;font-weight:600;color:#57606a;margin-bottom:3px;text-transform:uppercase;letter-spacing:.04em}
.user .who{color:#cfe3ff}
.tool{margin:6px 0 6px 18px;border:1px solid #d0d7de;border-left:4px solid #8250df;border-radius:8px;background:#fbfaff;max-width:88%}
.tool.write{border-left-color:#bf8700;background:#fffbf0} .tool.danger{border-left-color:#cf222e;background:#fff6f6}
.th{padding:6px 12px;font:12.5px "JetBrains Mono","DejaVu Sans Mono",Menlo,monospace;color:#24292f}
.th b{color:#8250df} .write .th b{color:#9a6700} .danger .th b{color:#cf222e}
.th .ms{float:right;color:#8c959f}
pre{margin:0;padding:6px 12px 8px;font:12px/1.45 "JetBrains Mono","DejaVu Sans Mono",Menlo,monospace;color:#57606a;white-space:pre-wrap;word-break:break-word;border-top:1px dashed #d0d7de}
table{border-collapse:collapse;width:100%;font-size:13px} th{text-align:left;background:#f6f8fa;color:#57606a;font-weight:600}
th,td{border-bottom:1px solid #eaeef2;padding:7px 9px;vertical-align:top}
code{font:12.5px "JetBrains Mono","DejaVu Sans Mono",Menlo,monospace}
.badge{display:inline-block;font-size:11px;font-weight:600;border-radius:10px;padding:1px 8px;white-space:nowrap}
.r{background:#dafbe1;color:#116329} .w{background:#fff1c2;color:#7d4e00} .d{background:#ffebe9;color:#a40e26}
.ok{color:#116329;font-weight:600} .den{color:#a40e26;font-weight:600} .rej{color:#9a6700;font-weight:600}
.nw{white-space:nowrap} .req{font-weight:700} .opt{color:#57606a}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:16px}
.card{border:1px solid #d0d7de;border-radius:8px;padding:10px 12px;font-size:13px;line-height:1.6}
"""


def window(title, inner):
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body><div class="win">
<div class="bar"><span class="dot" style="background:#ff5f56"></span><span class="dot" style="background:#ffbd2e"></span>
<span class="dot" style="background:#27c93f"></span><span class="t">{html.escape(title)}</span></div>
<div class="body">{inner}</div></div></body></html>"""


def md(text):
    t = html.escape(text or "").strip()
    t = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", t)
    t = re.sub(r"`(.+?)`", r"<code>\1</code>", t)
    t = re.sub(r"(?m)^\s*[-*] ", "• ", t)
    t = re.sub(r"\n{2,}", "\n\n", t)
    return t.replace("\n\n", "<br><br>").replace("\n", "<br>")


def short_result(text, limit=520):
    try:
        obj = json.loads(text)
        if isinstance(obj, dict):
            obj.pop("next", None)
        s = json.dumps(obj, ensure_ascii=False, separators=(", ", ": "))
    except ValueError:
        s = text
    return s if len(s) <= limit else s[:limit] + " …"


def transcript_page(t):
    parts = [f"<h2>Kimi <code>{html.escape(t['model'])}</code> working through the MCP bridge</h2>",
             "<div class='sub'>OpenAI-compatible tool calling; every tool call goes to <code>POST /mcp</code> "
             "(Streamable HTTP) with a write-scoped API key. Writes are prepare → human approval → confirm.</div>"]
    for e in t["events"]:
        if e["type"] == "user":
            parts.append(f"<div class='msg user'><div class='bubble'><div class='who'>Support agent</div>{md(e['text'])}</div></div>")
        elif e["type"] == "assistant":
            parts.append(f"<div class='msg assistant'><div class='bubble'><div class='who'>Kimi</div>{md(e['text'])}</div></div>")
        else:
            cls = "danger" if e["name"] == "confirm_action" else "write" if e["name"].startswith(("prepare", "cancel")) else ""
            args = html.escape(json.dumps(e["arguments"], ensure_ascii=False))
            parts.append(f"<div class='tool {cls}'><div class='th'>→ <b>{e['name']}</b>({args})<span class='ms'>{e['ms']} ms"
                         f"{' · error' if e['isError'] else ''}</span></div><pre>{html.escape(short_result(e['result']))}</pre></div>")
    return window("MCP host demo · scripts/kimi_agent_demo.py", "".join(parts))


async def mcp_inventory(url, key):
    async with create_mcp_http_client(headers={"Authorization": f"Bearer {key}"}) as http, \
            streamable_http_client(url, http_client=http) as streams:
        async with ClientSession(streams[0], streams[1]) as s:
            init = await s.initialize()
            return (init, (await s.list_tools()).tools, (await s.list_resources()).resources,
                    (await s.list_resource_templates()).resource_templates, (await s.list_prompts()).prompts)


def tools_page(url, key):
    init, tools, resources, templates, prompts = asyncio.run(mcp_inventory(url, key))
    order = ["search_orders", "get_order", "find_customers", "check_inventory", "sales_summary", "prepare_refund",
             "prepare_stock_adjustment", "confirm_action", "cancel_action", "list_pending_actions"]
    tools = sorted(tools, key=lambda t: order.index(t.name) if t.name in order else 99)
    rows = []
    for t in tools:
        a = t.annotations
        if a and a.read_only_hint and not t.name.startswith("list_pending"):
            badge = "<span class='badge r'>read-only</span>"
        elif a and a.destructive_hint and not a.read_only_hint:
            badge = "<span class='badge d'>destructive · write</span>"
        else:
            badge = "<span class='badge w'>write scope</span>"
        props = (t.input_schema or {}).get("properties", {})
        req = set((t.input_schema or {}).get("required", []))
        params = ", ".join(f"<span class='{'req' if p in req else 'opt'}'>{p}</span>" for p in props) or "–"
        desc = html.escape(re.split(r"(?<=\.)\s", (t.description or "").strip())[0])
        rows.append(f"<tr><td><code>{t.name}</code></td><td>{badge}</td><td><code>{params}</code></td><td>{desc}</td></tr>")
    res = "".join(f"<div><code>{html.escape(str(r.uri))}</code> – {html.escape(r.description or '')}</div>" for r in resources)
    res += "".join(f"<div><code>{html.escape(r.uri_template)}</code> – {html.escape(r.description or '')}</div>" for r in templates)
    pr = "".join(f"<div><code>{html.escape(p.name)}</code>({', '.join(a.name for a in (p.arguments or []))}) – "
                 f"{html.escape(p.description or '')}</div>" for p in prompts)
    inner = (f"<h2>{html.escape(init.server_info.name)} {html.escape(init.server_info.version)} · tools/list</h2>"
             f"<div class='sub'>Protocol {html.escape(str(init.protocol_version))} over Streamable HTTP at <code>{html.escape(url)}</code>. "
             "Bold parameters are required. A read-only API key can call only the green tools.</div>"
             f"<table><tr><th>Tool</th><th>Access</th><th>Parameters</th><th>Description</th></tr>{''.join(rows)}</table>"
             f"<div class='grid'><div class='card'><b>Resources</b>{res}</div><div class='card'><b>Prompts</b>{pr}</div></div>")
    return window("MCP tools · " + url, inner)


def get_json(base, path, key):
    req = urllib.request.Request(base + path, headers={"X-API-Key": key})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def audit_page(base, key):
    actions = get_json(base, "/api/bridge/actions?limit=6", key)
    audit = list(reversed(get_json(base, "/api/bridge/audit?limit=14", key)))   # oldest first reads like a story
    status_cls = {"CONFIRMED": "ok", "CANCELLED": "rej", "FAILED": "den", "PENDING": ""}
    arows = "".join(
        f"<tr><td><code>{a['token'][:12]}…</code></td><td>{html.escape(a['kind'])}</td><td>{html.escape(a['summary'])}</td>"
        f"<td class='nw'>{html.escape(a['requested_by'])}</td><td class='{status_cls.get(a['status'], '')}'>{a['status']}</td></tr>"
        for a in actions)
    out_cls = {"OK": "ok", "DENIED": "den", "REJECTED": "rej", "ERROR": "den"}
    rows = "".join(
        f"<tr><td>{str(r['created_at'])[11:19]}</td><td class='nw'>{html.escape(r['api_key'])}</td><td><code>{r['tool']}</code></td>"
        f"<td><code>{html.escape(r['arguments'][:80])}</code></td><td class='{out_cls[r['outcome']]}'>{r['outcome']}</td>"
        f"<td>{html.escape(r.get('detail') or '')}</td><td class='nw'>{r['duration_ms']} ms</td></tr>" for r in audit)
    inner = ("<h2>Write actions: prepared → approved → confirmed</h2>"
             "<div class='sub'><code>GET /api/bridge/actions</code> · tokens are single-use, expire after 5 minutes and belong to the key that prepared them</div>"
             f"<table><tr><th>Token</th><th>Kind</th><th>Summary shown to the human</th><th>Key</th><th>Status</th></tr>{arows}</table>"
             "<h2 style='margin-top:22px'>Tool audit log</h2>"
             "<div class='sub'><code>GET /api/bridge/audit</code> · one row per MCP tool call, including calls refused for missing scope (UTC)</div>"
             f"<table><tr><th>Time</th><th>API key</th><th>Tool</th><th>Arguments</th><th>Outcome</th><th>Detail</th><th>Took</th></tr>{rows}</table>")
    return window("Bridge admin API · " + base, inner)


def shot(page, content, name):
    page.set_content(content)
    page.wait_for_timeout(200)
    page.locator(".win").screenshot(path=str(OUT / name))
    print("wrote", OUT / name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--key", default="dev-support-key-change-me")
    ap.add_argument("--chrome", action="store_true")
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    transcript = json.loads((ROOT / "docs" / "demo" / "transcript.json").read_text())
    tools_html = tools_page(args.base_url + "/mcp", args.key)
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome" if args.chrome else None)
        page = browser.new_page(viewport={"width": 1240, "height": 900}, device_scale_factor=2)
        shot(page, transcript_page(transcript), "transcript.png")
        shot(page, tools_html, "tools.png")
        shot(page, audit_page(args.base_url, args.key), "audit.png")
        page.goto(args.base_url + "/swagger-ui.html")
        page.wait_for_selector(".opblock", timeout=20000)
        page.wait_for_timeout(800)
        page.screenshot(path=str(OUT / "swagger.png"))
        print("wrote", OUT / "swagger.png")
        browser.close()


if __name__ == "__main__":
    main()
