# popclone

just another app cloner for android, with a very specific aim! ~pop pop

clone any installed app as many times as you want, each clone keeps its own data and has its own device ids. several clones can run at the same time. works on android 10 to 16.

google sign in inside clones is a work in progress (branch gms): the add dialog can clone google play services as a helper that the other clones talk to.

## build

```
./gradlew :app:assembleDebug
```

needs java 17 and the android sdk (set `sdk.dir` in `local.properties`).
the apk ends up in `app/build/outputs/apk/debug/`.

## build options

- `-Pbridge`: for x86 hosts that translate arm (waydroid). makes the apk arm64 only, do not use it on a real phone.
- `-PappId=dev.popclone.dev`: installs a second copy next to the real one, handy for testing without touching your clones.
