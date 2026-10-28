# TV Dash

An Android TV dashboard for free, legal IPTV playlists, themed in Colorado Avalanche colours (colours only, no logos).

- Long-press a tile to pin, favourite or Move it: ◀ ▶ (and ▲ ▼ in the grid) slide it, OK drops, Back cancels. Works for apps, categories and Favourites channels
- Apps row with pinning, channel categories, favourites and recently watched
- TV guide (XMLTV): now and next on tiles and in the player
- Offline streams hidden automatically
- Sports score ticker (ESPN public feeds) with Avalanche game mode
- Goal alerts with scorer and assists from the NHL feed (checked every 10 s during a live game), on the dashboard and in the player
- Goal horn and flashing red goal light with every goal alert. Plays the Avalanche horn in `app/src/main/res/raw/goal_horn.mp3` (first 10 seconds); without that file it falls back to a horn generated in code. Turn the horn off on the phone setup page
- Phone setup page: open "📱 Phone setup" on the TV, scan the QR code, and change playlists, TV guide link and team from your phone (same Wi-Fi, port 8080)
- Player: ▲/▼ change channel, Back jumps to the last channel (Back again to exit)
- Screensaver after 5 minutes idle

## Screen fit
Built for a 55" 4K TV (RCA RTGU5570). Everything is sized from the screen's real height, inside TV-safe margins, so the whole dashboard is on screen at 1080p or 4K with no scrolling. Rows show as many tiles as fit; extra tiles go on pages behind a "More ▸" tile. The score ticker is the one thing that moves: it scrolls sideways, with your team's game fixed at the left.

## Install on the TV
Step-by-step: **[INSTALL.md](INSTALL.md)** (build the APK, then send it to the TV over Wi-Fi with ADB, a USB stick, or Android Studio).

## Setup
Playlist and guide links are in `app/src/main/java/com/matt/tvdash/Data.kt` (`PLAYLIST_URL`, `EPG_URL`).
Your team is `FAV_TEAM` in `Ticker.kt`. Put an image at `app/src/main/res/drawable/centre_ice.png` to show it at centre ice.
