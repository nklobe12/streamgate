# StreamGate

Fire TV app that opens the site full-screen, stays on it, and blocks ads, popups, and off-site redirects.

Remote: D-pad moves the pointer, OK clicks, Back goes back. Push the pointer past the top or bottom edge to scroll.

Build: every push to `main` publishes `StreamGate.apk` to the `latest` GitHub release.

If something legitimate is blocked, the app shows `Blocked: <host>`. Ad domains are listed in `app/src/main/assets/blocked_hosts.txt`.
