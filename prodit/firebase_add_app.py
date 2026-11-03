#!/usr/bin/env python3
"""Зарегистрировать Android-приложение com.prodit.meet в Firebase-проекте Умного плеера (disl-da638) и добавить его
ключи в ../google-services.json (записи плеера не трогаются; копия — google-services.json.bak-<дата>).
Запуск: python3 ~/infra/hn-archive-app/prodit/firebase_add_app.py — затем пересобрать prodit/build_prodit.sh."""
import base64, json, os, shutil, time, urllib.error, urllib.request
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PKG = "com.prodit.meet"
sa = json.load(open(os.path.join(ROOT, "fcm-service-account.json")))
b64 = lambda b: base64.urlsafe_b64encode(b).rstrip(b"=").decode()
now = int(time.time())
h = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
p = b64(json.dumps({"iss": sa["client_email"], "scope": "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/firebase",
                    "aud": sa["token_uri"], "iat": now, "exp": now + 3600}).encode())
k = serialization.load_pem_private_key(sa["private_key"].encode(), None)
sig = b64(k.sign(f"{h}.{p}".encode(), padding.PKCS1v15(), hashes.SHA256()))
tok = json.loads(urllib.request.urlopen(urllib.request.Request(sa["token_uri"],
      data=f"grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion={h}.{p}.{sig}".encode())).read())["access_token"]


def api(url, body=None):
    r = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                               headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try:
        return json.loads(urllib.request.urlopen(r).read())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Firebase: HTTP {e.code} {e.read().decode()[:400]}")


P = f"https://firebase.googleapis.com/v1beta1/projects/{sa['project_id']}"
app = next((a for a in api(P + "/androidApps").get("apps", []) if a["packageName"] == PKG), None)
if not app:
    op = api(P + "/androidApps", {"packageName": PKG, "displayName": "Production IT"})
    for _ in range(30):
        o = api("https://firebase.googleapis.com/v1beta1/" + op["name"])
        if o.get("done"):
            app = o["response"]
            break
        time.sleep(2)
print("Firebase app:", app["appId"])
new = json.loads(base64.b64decode(api(f"https://firebase.googleapis.com/v1beta1/{app['name']}/config")["configFileContents"]))
gsf = os.path.join(ROOT, "google-services.json")
shutil.copy(gsf, gsf + ".bak-" + time.strftime("%Y%m%d-%H%M"))
gs = json.load(open(gsf))
gs["client"] = [c for c in gs["client"] if c["client_info"]["android_client_info"]["package_name"] != PKG] + \
               [c for c in new["client"] if c["client_info"]["android_client_info"]["package_name"] == PKG]
json.dump(gs, open(gsf, "w"), indent=2)
print("google-services.json:", [c["client_info"]["android_client_info"]["package_name"] for c in gs["client"]])
