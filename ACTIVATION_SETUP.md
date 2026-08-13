# Activation Setup — Connecting the App to the Admin Panel

Step-by-step guide for going from "nothing deployed" to "phone is activated and talking to a live admin panel." This is the practical companion to `DOCUMENTATION.md` (architecture) and `admin-panel/README.md` (local dev setup) — this file is specifically about wiring the two together for real, once.

## 1. Get a MongoDB database (free, ~5 minutes)

The admin panel needs somewhere to store license codes and signups. Do this whether you're testing locally or deploying.

1. Sign up at https://www.mongodb.com/cloud/atlas/register
2. Create a free **M0** cluster
3. **Database Access** → add a database user (username + password)
4. **Network Access** → add `0.0.0.0/0` (allow from anywhere) — required so both your deployed admin panel *and* phones out in the world can reach it, not just your laptop
5. **Connect → Drivers** → copy the connection string, then add `/octapad` before the `?`:
   ```
   mongodb+srv://<user>:<password>@<cluster>.mongodb.net/octapad?retryWrites=true&w=majority
   ```
   Keep this string — you'll need it in two places below.

## 2. Deploy the admin panel

From inside `admin-panel/`:

```bash
npx vercel
```

- Log in (or create a free account) when prompted, in the browser window it opens
- Accept the defaults — it auto-detects Next.js
- You'll get a live URL like `https://octapad-admin-xyz.vercel.app` — **save this**, it's the "Server URL" from step 5

## 3. Set environment variables on Vercel

This is the step that's easy to miss — your local `.env.local` file does **not** carry over to Vercel automatically.

1. Vercel dashboard → your project → **Settings → Environment Variables**
2. Add:
   | Key | Value |
   |---|---|
   | `MONGODB_URI` | the connection string from step 1 |
   | `ADMIN_PASSWORD` | whatever password you want to log into the dashboard with |
3. Redeploy (Vercel → Deployments → ⋯ → Redeploy) so the new env vars actually take effect

**If you skip this step**: the dashboard login and every app activation attempt will fail with a server error (500), because the deployed app has no database connection string.

## 4. Log in and generate an activation code

1. Open your Vercel URL in a browser → redirects to `/login`
2. Enter the `ADMIN_PASSWORD` you set in step 3
3. **Dashboard → Licenses tab → "Generate activation code(s)"**
4. You get a code like `AB3D-9KXQ-7M2P` — this is what goes on the phone

You can generate as many as you want (bulk count + an optional note, e.g. "dealer batch #3"). Each one locks to the first device that redeems it.

## 5. Activate the app on a phone

1. Open the app → splash screen → **Activation screen**
2. Tap **"▸ SERVER URL"** to expand it, paste your Vercel URL from step 2 (e.g. `https://octapad-admin-xyz.vercel.app`)
3. Enter the activation code from step 4 (Name/Phone are optional but get sent to your Signups tab if filled in)
4. Tap **ACTIVATE**

The server URL is saved locally after this — you only enter it once per install, not every launch.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Couldn't reach the server" on the phone | Server URL typo, or the Vercel deployment isn't live — try opening the URL in a phone browser first to confirm it loads |
| Dashboard login fails / activation fails with a server error | `MONGODB_URI` or `ADMIN_PASSWORD` not set on Vercel (step 3), or set but not redeployed since |
| "That activation code doesn't exist" | Code typo (they use `A-Z2-9`, no `0/O/1/I` to avoid confusion) — copy-paste rather than retyping |
| "This code is already activated on a different device" | Someone (maybe you, testing) already redeemed it on another phone — generate a new code, or unbind the old one from the dashboard's Licenses tab |
| Works locally (`npm run dev` + LAN IP) but not after deploying | Re-check step 3 specifically — this is the #1 cause once local testing already works |

## Testing locally first (optional, before deploying)

You don't have to deploy to try this end-to-end. Run `npm run dev` in `admin-panel/`, find your laptop's LAN IP (`ipconfig` on Windows, `ifconfig`/`ipconfig getifaddr en0` on Mac), and use `http://<that-ip>:3000` as the Server URL — works as long as the phone and laptop are on the same WiFi. Steps 1, 4, and 5 above are identical either way; only step 2/3 (Vercel) is skipped.
