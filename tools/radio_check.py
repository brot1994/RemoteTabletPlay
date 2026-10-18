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

# ---- uji mendalam link tambahan (HLS diikuti sampai segmen audio, SSL dicek dua cara)
import ssl, subprocess
def fetch(url, verify=True, n=20000):
    ctx = None if verify else ssl._create_unverified_context()
    r = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=10, context=ctx)
    return r.headers.get("Content-Type", ""), r.read(n), r.geturl()
def deep(url):
    res = {"url": url}
    for verify in (True, False):
        try:
            ct, data, final = fetch(url, verify)
            res["verify" if verify else "noverify"] = "ok"
            res["ct"] = ct; res["bytes"] = len(data)
            if data[:7] == b"#EXTM3U":
                txt = data.decode("utf8", "replace"); res["m3u8"] = txt[:400]
                cur = final
                for depth in range(3):
                    lines = [l for l in txt.splitlines() if l and not l.startswith("#")]
                    if not lines: break
                    nxt = urllib.parse.urljoin(cur, lines[-1 if depth else 0])
                    ct2, d2, cur = fetch(nxt, verify, 200000)
                    if d2[:7] == b"#EXTM3U": txt = d2.decode("utf8", "replace"); continue
                    res["segment"] = {"url": nxt, "ct": ct2, "bytes": len(d2)}; break
            break
        except Exception as e:
            res["verify" if verify else "noverify"] = str(e)[:150]
    host = urllib.parse.urlparse(url)
    if host.scheme == "https":
        try:
            o = subprocess.run(["openssl", "s_client", "-connect", f"{host.hostname}:{host.port or 443}", "-servername", host.hostname, "-showcerts"],
                               input=b"", capture_output=True, timeout=15).stdout.decode("latin1")
            res["chain"] = [l.strip() for l in o.splitlines() if l.strip().startswith(("s:", "i:"))][:8]
        except Exception as e:
            res["chain"] = str(e)
    return res
try:
    extra = [l.strip() for l in open("tools/radio_extra.txt") if l.strip()]
except FileNotFoundError:
    extra = []
json.dump([deep(u) for u in extra], open("tools/radio-extra.json", "w"), indent=1, ensure_ascii=False)
