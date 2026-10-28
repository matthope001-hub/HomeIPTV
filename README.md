# TV Dash

An Android TV dashboard for free, legal IPTV playlists, themed in Colorado Avalanche colours (colours only, no logos).

- Apps row with pinning, channel categories, favourites and recently watched
- TV guide (XMLTV): now and next on tiles and in the player
- Offline streams hidden automatically
- Sports score ticker (ESPN public feeds) with Avalanche game mode and goal alerts
- Player: ▲/▼ change channel, Back jumps to the last channel (Back again to exit)
- Screensaver after 5 minutes idle

## Setup
Open in Android Studio and run on an Android TV device or emulator.
Playlist and guide links are in `app/src/main/java/com/matt/tvdash/Data.kt` (`PLAYLIST_URL`, `EPG_URL`).
Your team is `FAV_TEAM` in `Ticker.kt`. Put an image at `app/src/main/res/drawable/centre_ice.png` to show it at centre ice.
