# StreamGate

Fire TV app that opens the site full-screen, stays on it, and blocks ads, popups, and off-site redirects.

Remote: the D-pad moves through tabs and game rows, OK opens a game, and Back goes back. During a game, press OK for streams and options.

Build: every push to `main` publishes `StreamGate.apk` to the `latest` GitHub release.

If something legitimate is blocked, the app shows `Blocked: <host>`. Ad domains are listed in `app/src/main/assets/blocked_hosts.txt`.
