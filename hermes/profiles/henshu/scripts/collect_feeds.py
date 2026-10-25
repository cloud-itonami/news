#!/usr/bin/env python3
"""Deterministic primary-source collector for henshu (itonami news editorial bot).

Fetches every feed registered in scripts/sources.json, normalizes the items,
and writes three files under workspace/ingest/<UTC-stamp>/:

  items.json      normalized, deduplicated items (flat: title/link/published/
                  excerpt/source metadata). Excerpt is capped at 280 chars.
  counts.json     deterministic bias counts: per source / country / kind,
                  with shares. This is the authority for the bias summary's
                  numbers; the LLM may interpret but never recompute them.
  fetch_log.json  one row per source: ok or the exact failure reason.

Evidence floor (root AGENTS.md "8 questions"):
  - prints SCANNED<TAB>sources_ok=<n><TAB>sources_total=<n><TAB>items=<n><TAB>errors=<n>
  - zero successfully fetched sources is NOT clean: exit 2 and say so
  - a failure is recorded per source, never silently dropped
  - exit 0 also when some sources failed; check the SCANNED line, not the exit code

Stdlib only. No new runtime dependencies (root AGENTS.md).
"""

import hashlib
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime

BASE = os.path.dirname(os.path.abspath(__file__))
PROFILE_HOME = os.path.dirname(BASE)
SOURCES_FILE = os.path.join(BASE, "sources.json")
INGEST_ROOT = os.path.join(PROFILE_HOME, "workspace", "ingest")

USER_AGENT = "henshu-collector/0.1 (+itonami; registered primary-source digest)"
FETCH_TIMEOUT_S = 25
EXCERPT_MAX = 280
ITEM_WINDOW_DAYS = 7


def strip_html(text):
    if not text:
        return ""
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"&nbsp;", " ", text)
    text = re.sub(r"&amp;", "&", text)
    text = re.sub(r"&lt;", "<", text)
    text = re.sub(r"&gt;", ">", text)
    text = re.sub(r"&#39;|&apos;", "'", text)
    text = re.sub(r"&quot;", '"', text)
    return re.sub(r"\s+", " ", text).strip()


def local_name(tag):
    return tag.split("}")[-1] if "}" in tag else tag


def child_text(elem, names):
    for child in elem.iter():
        if local_name(child.tag) in names and child.text:
            text = strip_html(child.text)
            if text:
                return text
    return ""


def child_link(entry):
    for child in entry.iter():
        if local_name(child.tag) != "link":
            continue
        href = child.get("href")
        if href:
            return href.strip()
        if child.text and child.text.strip():
            return child.text.strip()
    return ""


def normalize_date(raw):
    """ISO-8601 string or None. Never raises."""
    if not raw:
        return None
    raw = raw.strip()
    try:
        return parsedate_to_datetime(raw).astimezone(timezone.utc).isoformat()
    except (ValueError, TypeError):
        pass
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).isoformat()
    except ValueError:
        return None


def parse_feed(xml_text):
    """items from RSS 2.0 / RSS 1.0 (RDF) / Atom. Never raises on shape."""
    root = ET.fromstring(xml_text)
    items = []
    for elem in root.iter():
        if local_name(elem.tag) not in ("item", "entry"):
            continue
        title = child_text(elem, {"title"})
        link = child_link(elem)
        published = normalize_date(
            child_text(elem, {"pubDate", "published", "updated", "date", "dc:date"})
        )
        excerpt = strip_html(
            child_text(elem, {"description", "summary", "content", "encoded"})
        )[:EXCERPT_MAX]
        if not (title or link):
            continue
        items.append(
            {
                "title": title,
                "link": link,
                "published": published,
                "excerpt": excerpt,
            }
        )
    return items


def _ssl_context():
    """Default TLS context, with opportunistic CA bundle discovery.

    Some Python builds (macOS python.org framework) ship without a CA file and
    fail every https fetch with CERTIFICATE_VERIFY_FAILED. That is a broken
    measurement path, not a reason to disable verification. Prefer certifi when
    present, then well-known system bundles, then the platform default."""
    candidates = []
    try:
        import certifi  # opportunistic, not a hard dependency

        candidates.append(certifi.where())
    except Exception:  # noqa: BLE001
        pass
    candidates += [
        "/etc/ssl/cert.pem",
        "/private/etc/ssl/cert.pem",
        "/etc/ssl/certs/ca-certificates.crt",
    ]
    for ca in candidates:
        if ca and os.path.exists(ca):
            try:
                return ssl.create_default_context(cafile=ca)
            except OSError:
                continue
    return ssl.create_default_context()


SSL_CONTEXT = _ssl_context()


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=FETCH_TIMEOUT_S, context=SSL_CONTEXT) as resp:
        return resp.read().decode("utf-8", errors="replace")


def within_window(published):
    if not published:
        return True  # undated items are kept and flagged, never dropped silently
    try:
        dt = datetime.fromisoformat(published)
        return dt >= datetime.now(timezone.utc) - timedelta(days=ITEM_WINDOW_DAYS)
    except ValueError:
        return True


def main():
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(INGEST_ROOT, stamp)

    try:
        with open(SOURCES_FILE, encoding="utf-8") as fh:
            sources = json.load(fh)
    except (OSError, ValueError) as exc:
        print(f"COLLECTOR-ERROR\tcannot read {SOURCES_FILE}: {exc}")
        print("SCANNED\tsources_ok=0\tsources_total=0\titems=0\terrors=1")
        return 2

    items = []
    fetch_log = []
    seen_links = set()
    dropped_old = 0
    errors = 0

    for src in sources:
        sid = src.get("source-id", "?")
        url = src.get("url", "")
        try:
            xml_text = fetch(url)
            raw_items = parse_feed(xml_text)
        except urllib.error.HTTPError as exc:
            errors += 1
            fetch_log.append({"source-id": sid, "status": "error", "reason": f"HTTP {exc.code}"})
            print(f"SOURCE\t{sid}\tERROR\tHTTP {exc.code}")
            continue
        except Exception as exc:  # noqa: BLE001 - every failure must be recorded, not raised
            errors += 1
            reason = f"{type(exc).__name__}: {exc}"
            fetch_log.append({"source-id": sid, "status": "error", "reason": reason})
            print(f"SOURCE\t{sid}\tERROR\t{reason}")
            continue

        kept = 0
        for raw in raw_items:
            if not within_window(raw["published"]):
                dropped_old += 1
                continue
            key = raw["link"] or hashlib.sha256(
                (raw["title"] + sid).encode("utf-8")
            ).hexdigest()
            if key in seen_links:
                continue
            seen_links.add(key)
            items.append(
                {
                    "source-id": sid,
                    "host": src.get("host"),
                    "country": src.get("country"),
                    "kind": src.get("kind"),
                    "domain": src.get("domain"),
                    **raw,
                }
            )
            kept += 1
        fetch_log.append({"source-id": sid, "status": "ok", "items": kept})
        print(f"SOURCE\t{sid}\tok\t{kept}")

    def tally(field):
        counts = {}
        for it in items:
            counts[it.get(field) or "unknown"] = counts.get(it.get(field) or "unknown", 0) + 1
        total = sum(counts.values())
        return {
            "counts": dict(sorted(counts.items())),
            "total": total,
            "shares": {
                k: round(v / total, 4) if total else 0.0
                for k, v in sorted(counts.items())
            },
        }

    counts = {
        "generated-at": datetime.now(timezone.utc).isoformat(),
        "stamp": stamp,
        "sources-total": len(sources),
        "sources-ok": sum(1 for row in fetch_log if row["status"] == "ok"),
        "items-total": len(items),
        "dropped-outside-window": dropped_old,
        "by-source": tally("source-id"),
        "by-country": tally("country"),
        "by-kind": tally("kind"),
    }

    os.makedirs(out_dir, exist_ok=True)
    for name, payload in (
        ("items.json", items),
        ("counts.json", counts),
        ("fetch_log.json", fetch_log),
    ):
        with open(os.path.join(out_dir, name), "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False, indent=1, sort_keys=True)
            fh.write("\n")

    ok = counts["sources-ok"]
    print(f"INGEST\t{out_dir}")
    print(
        f"SCANNED\tsources_ok={ok}\tsources_total={len(sources)}"
        f"\titems={len(items)}\terrors={errors}"
    )
    if ok == 0:
        print("COLLECTOR-ERROR\tno source fetched successfully; this is a failed measurement, not an empty news day")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
