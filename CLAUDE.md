# Repo rules

- `INSTALL.md` explains how to get the app from the computer onto the TV (RCA RTGU5570, Google TV). Keep it accurate in every change: if a change affects building, installing, permissions, ports or first-run steps, update `INSTALL.md` in the same commit.
- The dashboard must fit the screen with no scrolling: size views in design units (1/540 of screen height, `dp()` in MainActivity), and page anything that doesn't fit. Exception: the sports ticker scrolls sideways (the user wants it), with the favourite team's game fixed at the left.
