"""Two measurements against the running development backend (single JVM, local PostgreSQL, fake DigiCoin):

1. catalog depth: perfect players (always correct, via the test-only oracle) — how far a run gets before the real
   1,000-product sample runs out of valid comparisons (G03.NO_PRODUCTS_LEFT) under the R-41 bands.
2. load baseline: N concurrent players each doing start → reveal/answer ×K → leave over HTTP; latency percentiles and
   errors per endpoint. A local baseline only — not a production capacity claim (T50).

usage: python3 qa/load.py [perfect_runs] [concurrency] [questions_per_run]"""
import concurrent.futures as cf, json, os, statistics, sys, time, urllib.error, urllib.request, uuid
sys.path.insert(0, os.path.dirname(__file__))
import oracle

BASE = os.environ.get("PE_BASE", "http://127.0.0.1:8080")
P = "/v1/gamification/price-expert"


def call(user, device, method, path, body=None):
    h = {"Authorization": "Bearer dev." + user, "X-Device-Id": device, "Content-Type": "application/json"}
    if method == "POST": h["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + P + path, method=method, data=None if body is None else json.dumps(body).encode(), headers=h)
    t = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            out = json.loads(r.read() or b"{}"); code = r.status
    except urllib.error.HTTPError as e:
        out = json.loads(e.read() or b"{}"); code = e.code
    return code, out, (time.perf_counter() - t) * 1000


def perfect_run(_):
    u, d = "pf" + uuid.uuid4().hex[:8], str(uuid.uuid4())
    code, s, _ = call(u, d, "POST", "/runs", {})
    run = s["run"]["run_id"]
    score = 0
    while True:
        code, v, _ = call(u, d, "POST", f"/runs/{run}/reveal", {})
        if code != 200 or v["state"] == "FINISHED":
            return {"score": score, "end": v.get("finish_reason") if code == 200 else v.get("code")}
        c = oracle.choice("u_" + u, True)
        code, a, _ = call(u, d, "POST", f"/questions/{v['question']['question_id']}/answer", {"choice": c})
        if a.get("result") != "correct":
            return {"score": score, "end": "unexpected " + str(a.get("result") or a.get("code"))}
        score = a["score"]
        if a.get("checkpoint_reached") and score >= 40:
            return {"score": score, "end": "stopped at 40"}


def load_player(k):
    u, d = "ld" + uuid.uuid4().hex[:8], str(uuid.uuid4())
    lat, errs = {}, []
    def rec(name, r):
        code, out, ms = r
        lat.setdefault(name, []).append(ms)
        if code >= 400: errs.append(f"{name} {code} {out.get('code')}")
        return out
    s = rec("start", call(u, d, "POST", "/runs", {}))
    run = s["run"]["run_id"]
    for _ in range(QPR):
        v = rec("reveal", call(u, d, "POST", f"/runs/{run}/reveal", {}))
        if v.get("state") != "QUESTION_OPEN": break
        rec("poll", call(u, d, "GET", f"/runs/{run}"))
        a = rec("answer", call(u, d, "POST", f"/questions/{v['question']['question_id']}/answer", {"choice": oracle.choice('u_' + u, True)}))
        if a.get("result") != "correct": break
    rec("leave", call(u, d, "POST", f"/runs/{run}/leave", {}))
    rec("result", call(u, d, "GET", f"/runs/{run}/result"))
    return lat, errs


if __name__ == "__main__":
    N = int(sys.argv[1]) if len(sys.argv) > 1 else 30
    C = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    QPR = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    with cf.ThreadPoolExecutor(8) as ex:
        runs = list(ex.map(perfect_run, range(N)))
    scores = sorted(r["score"] for r in runs)
    ends = {}
    for r in runs: ends[r["end"]] = ends.get(r["end"], 0) + 1
    print(json.dumps({"catalog_depth": {"runs": N, "score_min": scores[0], "score_median": statistics.median(scores), "score_max": scores[-1],
                                        "ends": ends, "scores": scores}}, ensure_ascii=False))
    t = time.time()
    with cf.ThreadPoolExecutor(C) as ex:
        res = list(ex.map(load_player, range(C * 3)))
    wall = time.time() - t
    all_lat, all_err = {}, []
    for lat, errs in res:
        for k, v in lat.items(): all_lat.setdefault(k, []).extend(v)
        all_err.extend(errs)
    q = lambda v, p: sorted(v)[min(len(v) - 1, int(p * len(v)))]
    total = sum(len(v) for v in all_lat.values())
    print(json.dumps({"load": {"players": C * 3, "concurrency": C, "questions_per_run": QPR, "requests": total, "wall_s": round(wall, 1),
                               "throughput_rps": round(total / wall, 1), "errors": len(all_err), "error_samples": all_err[:5],
                               "latency_ms": {k: {"p50": round(q(v, .5), 1), "p95": round(q(v, .95), 1), "max": round(max(v), 1)} for k, v in all_lat.items()}}},
                     ensure_ascii=False))
