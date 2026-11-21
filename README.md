# CoWorld

CoWorld is a Fabric mod pair (server mod + client mod) for Minecraft 26.3 that lets a small server support many players. Instead of the server ticking every loaded chunk for everyone, each player running the client mod takes over simulating the chunks around them on their own PC. Their computer ticks its surroundings and streams the results back, while the server keeps the authoritative world that all other players see live. Players without the mod connect normally and the server ticks everything for them, exactly like vanilla.

CoWorld is inspired by [MultiPaper](https://github.com/MultiPaper/MultiPaper), which spreads chunk ticking across multiple servers. CoWorld turns the idea around: the extra machines are the players' own PCs.

For the full core model, see [docs/coremodel.md](docs/coremodel.md).
