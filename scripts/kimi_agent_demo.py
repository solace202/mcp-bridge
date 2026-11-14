#!/usr/bin/env python3
"""LLM client demo: Kimi (OpenAI-compatible tool calling) using the bridge's MCP tools over Streamable HTTP.

The script is a minimal MCP host: it connects to /mcp with an API key, turns the MCP tool list into OpenAI
"tools", runs the model's tool calls through MCP, and plays the human for the approval step.

    pip install mcp openai
    export MOONSHOT_API_KEY=...            # never commit it
    python scripts/kimi_agent_demo.py --url http://localhost:8080/mcp --key dev-support-key-change-me

Writes docs/demo/transcript.json and docs/demo/transcript.md.
"""
import argparse
import asyncio
import json
import os
import pathlib
import time

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.shared._httpx_utils import create_mcp_http_client
from openai import OpenAI

ROOT = pathlib.Path(__file__).resolve().parent.parent

SYSTEM = (
    "You are a support assistant for a small online coffee-gear shop. Use the tools to look things up; never guess "
    "order numbers or amounts. For refunds and stock changes always call the prepare_* tool first, show the user the "
    "summary, and call confirm_action only after the user explicitly approves. Be brief."
)

TURNS = [
    "Olivia Dubois (olivia.dubois48@example.com) says one of the two titanium hand grinders in her recent order "
    "arrived with a cracked handle. Find the order and refund that one grinder. Also tell me whether we need to "
    "reorder that grinder.",
    "Yes, approved. Go ahead with the refund.",
]


def to_openai_tools(tools):
    return [{"type": "function", "function": {"name": t.name, "description": t.description or "",
                                              "parameters": t.input_schema}} for t in tools]


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8080/mcp")
    ap.add_argument("--key", default=os.environ.get("MCP_API_KEY", "dev-support-key-change-me"))
    ap.add_argument("--model", default=os.environ.get("LLM_MODEL", "kimi-k3"))
    ap.add_argument("--base-url", default=os.environ.get("LLM_BASE_URL", "https://api.moonshot.cn/v1"))
    args = ap.parse_args()

    api_key = os.environ.get("MOONSHOT_API_KEY") or os.environ.get("LLM_API_KEY")
    if not api_key:
        raise SystemExit("Set MOONSHOT_API_KEY (or LLM_API_KEY)")
    llm = OpenAI(api_key=api_key, base_url=args.base_url)

    transcript = {"model": args.model, "server": args.url, "started": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
                  "tools": [], "events": []}
    async with create_mcp_http_client(headers={"Authorization": f"Bearer {args.key}"}) as http, \
            streamable_http_client(args.url, http_client=http) as streams:
        async with ClientSession(streams[0], streams[1]) as session:
            init = await session.initialize()
            tools = (await session.list_tools()).tools
            transcript["serverInfo"] = {"name": init.server_info.name, "version": init.server_info.version}
            transcript["tools"] = [{"name": t.name, "title": t.title, "description": t.description,
                                    "readOnly": bool(t.annotations and t.annotations.read_only_hint),
                                    "destructive": bool(t.annotations and t.annotations.destructive_hint),
                                    "params": list((t.input_schema or {}).get("properties", {}).keys()),
                                    "required": (t.input_schema or {}).get("required", [])} for t in tools]
            openai_tools = to_openai_tools(tools)
            messages = [{"role": "system", "content": SYSTEM}]

            for user_text in TURNS:
                messages.append({"role": "user", "content": user_text})
                transcript["events"].append({"type": "user", "text": user_text})
                for _ in range(10):
                    resp = llm.chat.completions.create(model=args.model, messages=messages, tools=openai_tools,
                                                       extra_body={"thinking": {"type": "disabled"}})
                    msg = resp.choices[0].message
                    messages.append(msg.model_dump(exclude_none=True))
                    if not msg.tool_calls:
                        transcript["events"].append({"type": "assistant", "text": msg.content})
                        break
                    for call in msg.tool_calls:
                        call_args = json.loads(call.function.arguments or "{}")
                        started = time.perf_counter()
                        result = await session.call_tool(call.function.name, call_args)
                        text = "\n".join(c.text for c in result.content if getattr(c, "type", "") == "text")
                        transcript["events"].append({"type": "tool", "name": call.function.name, "arguments": call_args,
                                                     "isError": bool(result.is_error), "result": text,
                                                     "ms": round((time.perf_counter() - started) * 1000)})
                        messages.append({"role": "tool", "tool_call_id": call.id, "content": text})

    out = ROOT / "docs" / "demo"
    out.mkdir(parents=True, exist_ok=True)
    (out / "transcript.json").write_text(json.dumps(transcript, indent=2, ensure_ascii=False))
    lines = [f"# Kimi ({args.model}) using the MCP bridge", ""]
    for e in transcript["events"]:
        if e["type"] == "user":
            lines += [f"**User:** {e['text']}", ""]
        elif e["type"] == "assistant":
            lines += [f"**Assistant:** {e['text']}", ""]
        else:
            lines += [f"`{e['name']}({json.dumps(e['arguments'], ensure_ascii=False)})`" + (" -> error" if e["isError"] else ""),
                      "", "```json", e["result"][:1500], "```", ""]
    (out / "transcript.md").write_text("\n".join(lines))
    for e in transcript["events"]:
        print(e["type"].upper(), e.get("name", ""), (e.get("text") or json.dumps(e.get("arguments")))[:300])


if __name__ == "__main__":
    asyncio.run(main())
