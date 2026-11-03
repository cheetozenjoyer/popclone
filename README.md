# popclone

just another app cloner for android, with a very specific aim! ~pop pop

clone any installed app as many times as you want, each clone keeps its own data and has its own device ids. works on android 10 to 16.

## build

```
./gradlew :app:assembleDebug
```

needs java 17 and the android sdk (set `sdk.dir` in `local.properties`).
the apk ends up in `app/build/outputs/apk/debug/`.
