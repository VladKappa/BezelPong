# BezelPong

Bezel-controlled pong game for Wear OS. Rotate the bezel to move the paddle around the ring and keep the ball in play.

## Gameplay

https://github.com/user-attachments/assets/c8e91546-3672-4cbf-9a81-c140ccf0fda8

## Controls

- Rotate bezel: move paddle around the ring
- Tap: start/restart
- Tap gear: open settings (sensitivity, reset high score)

## Features

- Circular pong arena with wall-bounce indicator ring
- Ball speed increase as the score gets higher.
- High score saved with DataStore
- Sensitivity control for rotary input

## Build / Run

Requirements: Android Studio, Wear OS emulator or device, minSdk 30.

```bash
./gradlew :app:installDebug
```

Then launch the app on your watch/emulator.

## Notes

- Made and tested on a Galaxy Watch 6 Classic (physical bezel). Not tested on other devices.
- Best for watches with a physical bezel, otherwise the controls would be a nightmare.
- Built with LLM help because I am bad at math and do not know Kotlin; this was pure interest.
- Use Android Studio to build, run, and deploy.
- To run on a watch, use Wireless debugging on the watch and pair it with Android Studio.
