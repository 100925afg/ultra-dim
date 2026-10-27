import urllib.request, ssl, sys, json, os
PROXY = "http://127.0.0.1:7897"
ctx = ssl._create_unverified_context()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": PROXY, "https": PROXY}), urllib.request.HTTPSHandler(context=ctx))
def get(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 (Windows NT 10.0; Win64; x64)","Accept":"*/*"})
    r = opener.open(req, timeout=timeout)
    return r.status, r.read().decode("utf-8","replace")
if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "probe":
        for u in sys.argv[2:]:
            try:
                s,b = get(u)
                print(s, len(b), u)
            except Exception as e:
                print("ERR", type(e).__name__, str(e)[:80], u)
    elif mode == "save":
        u, path = sys.argv[2], sys.argv[3]
        s,b = get(u)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path,"w",encoding="utf-8").write(b)
        print("saved", s, len(b), path)
    elif mode == "grep":
        u, pat = sys.argv[2], sys.argv[3]
        s,b = get(u)
        n=0
        for i,line in enumerate(b.splitlines(),1):
            if pat.lower() in line.lower():
                print(i, line[:300]); n+=1
        print("matches", n)

def get_b64(url, timeout=60):
    import base64
    s, b = get(url, timeout)
    return base64.b64decode(b).decode("utf-8", "replace")
