# Bounty Slap (Fabric 1.21.11, client-side)

- A bounty addition of $2.5M or more slaps the wanted poster on screen for 3 seconds max,
  with "<NAME> Added <AMOUNT> to the Bounty!" above it. A newer bounty restarts the animation.
- Every bounty addition (any amount) shows a small top-right notification with the player's head.
- F9 = toggle the poster, F4 = toggle notifications. Both can be rebound in Options > Controls > Key Binds > Bounty Slap.

Test commands: /bountytest <name> <amount>  (e.g. /bountytest Steve 5M)   and   /bountydebug

Build: push this folder to GitHub (the .github folder must come along), open Actions > build,
download "bounty-slap-jar" and use the jar WITHOUT "-sources". Or run `gradle build` locally with JDK 21.
