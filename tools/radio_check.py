import json, urllib.request, urllib.parse, socket, time, sys
socket.setdefaulttimeout(8)
Q = ["prambors", "sonora", "jak fm", "jakfm", "gen fm", "gen 98", "delta fm", "delta", "suara surabaya", "elshinta"]
UA = {"User-Agent": "TabletPlay/1.7 radio-check"}
def get(u):
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA)))
servers = ["de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info", "all.api.radio-browser.info"]
def api(path):
    for s in servers:
        try: return get("https://" + s + path)
        except Exception as e: err = e
    raise err
seen = {}
for q in Q:
    for st in api("/json/stations/search?" + urllib.parse.urlencode({"name": q, "countrycode": "ID", "limit": 40})):
        seen[st["stationuuid"]] = st
def probe(url):
    t = time.time()
    try:
        r = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Icy-MetaData": "1"}), timeout=8)
        ct = r.headers.get("Content-Type", ""); data = r.read(16384)
        return {"ok": len(data) > 4000, "status": r.status, "ct": ct, "bytes": len(data), "final": r.geturl(),
                "icy": r.headers.get("icy-name", ""), "ms": int((time.time() - t) * 1000), "head": data[:60].decode("latin1") if "mpegurl" in ct.lower() or data[:7] == b"#EXTM3U" else ""}
    except Exception as e:
        return {"ok": False, "err": str(e)[:120]}
out = []
for st in seen.values():
    res = {k: st.get(k) for k in ("name", "url", "url_resolved", "codec", "bitrate", "lastcheckok", "state", "favicon", "homepage", "clickcount", "tags")}
    res["probe"] = probe(st["url_resolved"] or st["url"])
    out.append(res)
out.sort(key=lambda r: (r["name"] or "").lower())
json.dump(out, open("tools/radio-check.json", "w"), indent=1, ensure_ascii=False)
print(len(out), "stations")
