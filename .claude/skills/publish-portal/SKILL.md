---
name: publish-portal
description: Test, build and publish the web portal (frontend/) to GitHub Pages by copying the Docker-built site into docs/. Use when the user asks to build, deploy, publish or update the web portal / website / frontend.
---

# Publish the web portal

Read `CLAUDE.md` (section "Web portal") first. Everything runs in Docker from `frontend/`; install nothing on the machine. The repo is **public**, so pushing is outward-facing: do the local steps freely and ask before `git push`.

## 0. Setup
```bash
export PATH="$PATH:/c/Program Files/Docker/Docker/resources/bin"   # Windows host
cd <repo>/frontend
docker image inspect vinayaka-portal-build >/dev/null 2>&1 || docker compose build
docker compose run --rm portal npm install
```

## 1. Test and build
```bash
docker compose run --rm portal npm test            # must show 0 failures
docker compose run --rm portal npm run build       # type-checks, then writes frontend/dist
```
`android/secrets.properties` must hold `SPREADSHEET_URL` and `GOOGLE_WEB_CLIENT_ID` (the build bakes them in). If either is missing the portal builds but cannot sign in; check
`grep -c googleusercontent dist/assets/*.js` is at least 1 (do not print the ids).

## 2. Copy into docs/ (what GitHub Pages serves)
```bash
rm -rf ../docs && cp -r dist ../docs && touch ../docs/.nojekyll
```
Never put anything else in `docs/`.

## 3. Check it locally (optional but quick)
`docker compose run --rm --service-ports portal npm run dev`, open http://localhost:5173, use "Try demo". Stop only your own `frontend-portal-run-*` container.

## 4. Commit and push only with the owner's OK
Stage `frontend/` (source, never `node_modules/` or `dist/`, both git-ignored) and `docs/`. Message `Portal: <what changed>` plus the co-author trailer from the session.
Ask "Push the portal to GitHub?" and on yes `git push origin master`. GitHub Pages republishes in about a minute.

## 5. One-time setup (tell the owner, they do it)
* GitHub: repository Settings, Pages, Source "Deploy from a branch", branch `master`, folder `/docs`.
* Google Cloud: on the Web OAuth client add Authorized JavaScript origins `https://laxmanarao5.github.io` (and `http://localhost:5173` for local runs).
* Without these two the page loads but "Sign in with Google" fails.

## 6. Report
Say what changed, the test count, and that real Google sign-in has not been exercised unless the owner confirmed it.
