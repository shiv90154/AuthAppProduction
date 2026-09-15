# Activation Setup — Connecting the App to the Admin Panel

Step-by-step guide for going from "nothing deployed" to "phone is activated and talking to a live admin panel." This is the practical companion to `DOCUMENTATION.md` (architecture) and `admin-panel/README.md` (deploy/local-dev details) — this file is specifically about wiring the two together for real, once.

**Current setup (2026-09-15): self-hosted on a VPS with Docker** — the admin panel and its own MongoDB both run in containers on a VPS, no MongoDB Atlas account or Vercel account needed. Vercel + Atlas still works as a fallback deployment target (see `admin-panel/README.md`'s "Deploying" section) if you'd rather not manage a VPS — swap steps 1-3 below for that section if so.

## 1. Get a VPS with Docker installed

Any VPS provider works (Hostinger, DigitalOcean, etc.) as long as Docker + the Docker Compose plugin are installed. SSH into it for the next step.

## 2. Deploy the admin panel + its database

```bash
git clone https://github.com/shiv90154/Octapad-adminpanelFinal.git octapad
cd octapad
cp .env.docker.example .env
nano .env   # fill in MONGO_ROOT_USER / MONGO_ROOT_PASSWORD / ADMIN_PASSWORD /
            # LICENSE_SIGNING_PRIVATE_KEY_B64 — see the comments in that file
            # for the one-line command that generates the signing keypair
docker compose up -d --build
```

If port `3000` is already used by something else on the VPS (check with `docker ps` / `ss -tlnp` first), edit `docker-compose.yml`'s `ports:` line to publish on a free port instead, e.g. `"3002:3000"` — the container-side port always stays `3000`.

The dashboard is then reachable at `http://<VPS_IP>:<port>` — this is your "Server URL" for step 5. No domain/HTTPS is set up by default (plain HTTP, matching the Android app's `usesCleartextTraffic`) — `COOKIE_SECURE` in `.env` must stay unset/`false` until a domain + HTTPS (Nginx/Caddy) is put in front of this, otherwise the login cookie gets silently dropped by the browser and `/login` looks like it does nothing.

## 3. Generate the license-signing keypair (if you haven't already)

`LICENSE_SIGNING_PRIVATE_KEY_B64` (from step 2's `.env`) must match the public key baked into the Android app (`LicenseToken.kt`'s `PUBLIC_KEY_B64`). If you're setting this up for the first time, generate a fresh pair:

```bash
docker run --rm node:20-alpine node -e "const c=require('crypto');const{publicKey,privateKey}=c.generateKeyPairSync('ec',{namedCurve:'prime256v1'});console.log('PRIVATE_PEM_B64='+Buffer.from(privateKey.export({type:'pkcs8',format:'pem'})).toString('base64'));console.log('PUBLIC_DER_B64='+publicKey.export({type:'spki',format:'der'}).toString('base64'));"
```

- `PRIVATE_PEM_B64` → paste into `.env`'s `LICENSE_SIGNING_PRIVATE_KEY_B64`, then `docker compose up -d --build` again.
- `PUBLIC_DER_B64` → paste into `app/src/main/java/com/example/myapplication/license/LicenseToken.kt`'s `PUBLIC_KEY_B64` constant, then rebuild the APK.

**Rotating this key logs out every already-activated device** — only do this once per deployment, not on every redeploy. Reuse the same value across redeploys/rebuilds.

## 4. Log in and generate an activation code

1. Open your Server URL in a browser → redirects to `/login`
2. Enter the `ADMIN_PASSWORD` you set in step 2's `.env`
3. **Dashboard → Licenses tab → "Generate activation code(s)"**
4. You get a code like `AB3D-9KXQ-7M2P` — this is what goes on the phone

You can generate as many as you want (bulk count + an optional note, e.g. "dealer batch #3"). Each one locks to the first device that redeems it.

## 5. Activate the app on a phone

1. Open the app → splash screen → **Activation screen**
2. Tap **"▸ SERVER URL"** to expand it — it's pre-filled with the current deployment's URL (`ActivationScreen.kt`'s fallback default), but paste your own Server URL from step 2 if it's different (e.g. `http://203.0.113.5:3002`)
3. Enter the activation code from step 4 (Name/Phone are optional but get sent to your Signups tab if filled in)
4. Tap **ACTIVATE**

The server URL is saved locally after this — you only enter it once per install, not every launch.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Couldn't reach the server" on the phone | Server URL typo, or the container isn't running (`docker ps` on the VPS) — try opening the URL in a phone browser first to confirm it loads |
| Dashboard login returns 200 but the page never changes / stays on `/login` | `COOKIE_SECURE=true` while the deployment is still plain HTTP — leave it unset/`false` until HTTPS is actually in front of the app |
| Dashboard login fails / activation fails with a server error (500) | `MONGO_ROOT_USER`/`MONGO_ROOT_PASSWORD`/`ADMIN_PASSWORD` not set in `.env` (step 2), or set but the container wasn't rebuilt since (`docker compose up -d --build`) |
| "That activation code doesn't exist" | Code typo (they use `A-Z2-9`, no `0/O/1/I` to avoid confusion) — copy-paste rather than retyping |
| "This code is already activated on a different device" | Someone (maybe you, testing) already redeemed it on another phone — generate a new code, or unbind the old one from the dashboard's Licenses tab |
| Phone shows "not verified"/signature error after a fresh app install | `LicenseToken.kt`'s `PUBLIC_KEY_B64` doesn't match the deployment's `LICENSE_SIGNING_PRIVATE_KEY_B64` — regenerate both together (step 3) and rebuild the APK, don't just change one side |
| Works locally (`npm run dev` + LAN IP) but not after deploying | Re-check step 2's `.env` — this is the #1 cause once local testing already works |

## Testing locally first (optional, before deploying)

You don't have to deploy to try this end-to-end. Run `npm run dev` in `admin-panel/` (needs a `.env.local` — see `admin-panel/README.md`), find your laptop's LAN IP (`ipconfig` on Windows, `ifconfig`/`ipconfig getifaddr en0` on Mac), and use `http://<that-ip>:3000` as the Server URL — works as long as the phone and laptop are on the same WiFi. Steps 4 and 5 above are identical either way; only steps 1-3 (VPS deploy) are skipped.
