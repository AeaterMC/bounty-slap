Bounty Slap 1.1.3 source update
================================

Changes:
- Repositioned the three leaderboard player-head slots and name/amount plaques to match the supplied /bountyboard reference.
- The leaderboard background now uses a full desert-landscape backdrop rather than a transparent/black-looking upper region.
- Player faces are rendered from PlayerInfo skin textures already cached by the Minecraft client; no external skin API was added.
- A Pirate Token animation cannot be interrupted by later bounty additions. Bounty additions received during the active 7.5-second token animation are ignored (including leaderboard recording and toast/slap effects).

Build:
1. Open this folder as a Gradle project with Java 21.
2. Run: gradle build
3. The mod JAR will be in build/libs/.

This archive includes the Gradle build configuration, which was missing from the supplied ZIP.
A prebuilt JAR is not included because this environment has no Gradle installation or network access to resolve Fabric Loom/Minecraft dependencies.
