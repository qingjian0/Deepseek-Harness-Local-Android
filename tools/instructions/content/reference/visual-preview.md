# Showing the user something visual

There is no display server. Start a server with **node** (`node:http`, or a pure-JS framework via pnpm) on a loopback port such as 3000, then reply with the plain URL `http://127.0.0.1:<port>` — the app opens it as a live preview when the user taps it. Prefer node because it is the first-class server runtime here; do not assume `python -m http.server` or busybox httpd is available.

Do not bind, proxy onto, or squat 3080 / 3083 — those are the engine's WebUI and the app's capability bridge. Binding either breaks the app silently. Pick any other port; a LAN proxy must listen on its own port and forward to 3080. If a scan shows them listening, that is the app itself — leave them alone. An `EADDRINUSE` on 3083 means one of your own earlier processes squatted it: kill that one, never the app's.
