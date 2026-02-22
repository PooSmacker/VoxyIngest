# voxyingest

a fabric mod that lets server admins send the entire world to a player's [voxy](https://github.com/mcrcortex/voxy) lod cache over the network. useful for pre populating lods on servers where players haven't explored yet.

## usage

run the command from the server console or as an op:

```
/voxyingest <player>
```

this sends every generated chunk in the player's current dimension. run it again to cancel an active transfer.

the player needs both voxy and voxyingest installed on their client. the server only needs voxyingest.

## how it works

the server reads .mca region files directly from disk on a background thread, which is much faster than going through minecrafts built in chunk io. each chunk's section data (blocks, biomes, light) gets compressed and sent as a single packet.

on the client side, sections are decoded and fed into voxy's standard import pipeline (`WorldConversionFactory.convert` -> `mipSection` -> `WorldUpdater.insertUpdate`), the same one voxy uses for its own `/voxy import` command. 


## building

requires the voxy source in the `voxy/` subfolder (gitignored so js git clone from cortex's repo). build voxy first, then build voxyingest:

```
cd voxy
./gradlew remapJar
cd ..
./gradlew build
```

the output jar is in `build/libs/`.

## requirements

- minecraft 1.21.11
- fabric loader 0.18.4+
- fabric api
- voxy (client side)
