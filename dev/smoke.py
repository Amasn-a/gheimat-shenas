"""Smoke run against the development backend: start → reveal → answer until a miss → offer → continue → ... → result."""
import json, sys, time, uuid, urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080"
P = BASE + "/v1/gamification/price-expert"
USER = sys.argv[2] if len(sys.argv) > 2 else "smoke" + uuid.uuid4().hex[:6]
DEV = str(uuid.uuid4())

def call(method, path, body=None, key=True):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(P + path, data=data, method=method)
    req.add_header("Authorization", "Bearer dev." + USER)
    req.add_header("X-Device-Id", DEV)
    req.add_header("Content-Type", "application/json")
    if method == "POST" and key: req.add_header("Idempotency-Key", str(uuid.uuid4()))
    try:
        with urllib.request.urlopen(req) as r: return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e: return e.code, json.loads(e.read() or b"{}")

s, b = call("GET", "/bootstrap"); print("bootstrap", s, b["quota"], "balance", b["balance"], "access", b["access"])
s, st = call("POST", "/runs", {}); print("start", s, st.get("code") or st["run"]["state"], st.get("run", {}).get("reference"))
run = st["run"]; rid = run["run_id"]
for i in range(40):
    s, rv = call("POST", f"/runs/{rid}/reveal", {})
    if rv["state"] == "FINISHED": print("finished at reveal", rv["finish_reason"]); break
    q = rv["question"]; ref = rv["reference"]["price"]
    choice = "higher" if i % 3 else "lower"
    s, ans = call("POST", f"/questions/{q['question_id']}/answer", {"choice": choice})
    print(f"q{i+1} ref={ref} choice={choice} -> {ans['result']} revealed={ans['revealed_price']} score={ans['score']} cp={ans['checkpoint_reached'] and ans['checkpoint_reached']['title']} cont={ans['continue_possible']} reason={ans['no_offer_reason']}")
    if ans["result"] == "wrong":
        if ans["continue_possible"]:
            time.sleep(1.7)
            s, act = call("POST", f"/runs/{rid}/offer", {})
            print("  activate", s, act["offer"], "balance", act["balance"], act["no_offer_reason"])
            if act["offer"]:
                s, op = call("POST", f"/offers/{act['offer']['offer_id']}/continue", {})
                print("  continue", s, op["status"], op["charge_status"], "run", op["run"]["state"])
                if op["run"]["state"] == "READY": continue
        break
s, res = call("GET", f"/runs/{rid}/result"); print("result", s, json.dumps(res, ensure_ascii=False)[:600])
time.sleep(1.5)
s, res = call("GET", f"/runs/{rid}/result"); print("result again: cp reward", res.get("checkpoint_reward"), "daily", call("GET", "/player/daily-reward")[1].get("state")), "balance", res.get("balance"))
s, pr = call("GET", f"/runs/{rid}/products"); print("products", s, len(pr.get("items", [])), "loss", pr.get("loss_product_id"))
