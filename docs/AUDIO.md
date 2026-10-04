# Audio

## Buses

`Master`, `Music`, `SFX`, `UI`, `Ambient` (extendable). Each bus has volume, mute and solo; a bus's
effective volume multiplies its parents', so `Master` scales everything. Buses are edited in the
Project settings and the **Audio** panel, and stored in `project.json`.

## Playing sounds

```js
audio.play("coin.wav");                 // SFX, one shot
audio.ui("click.wav");                  // UI bus
audio.music("theme.ogg");               // Music bus, looping
audio.fadeIn("boss.ogg", 2.5);          // fade in over 2.5 s, keeps a handle
var handle = audio.play("wind.ogg", 0.6);
audio.crossfade(handle, "calm.ogg", 1.5);   // 1.5 s crossfade
audio.setVolume("Music", 0.4);
audio.mute("SFX", true);
```

Kotlin: `AudioMixer.play(clip, bus, volume, pitch, loop, spatial, x, y, minDistance, maxDistance,
attenuation)`, `fade`, `crossfade`, `stopBus`, `stopAll`, `update(dt)`.

## Spatial sound

`AudioSource` nodes play positionally: distance to the listener (the camera) attenuates the gain with
one of three curves (linear, inverse, constant power) between `minDistance` and `maxDistance`. The
editor draws the audio areas overlay so ranges can be seen while placing emitters.

## Assets and preview

Audio assets live in `assets/`, are listed with sizes in the browser, and preview in the Audio panel
through the same mixer the game uses (including bus routing). Long music files stream; short SFX use
the SoundPool backend. A headless `NullAudioBackend` keeps tests and non-audio devices working.

## Fades and stops

* `fadeIn` on a voice ramps its gain from 0 to its base volume.
* `crossfade` fades the current voice out while the new one fades in.
* `stopBus("Music")` stops everything routed to that bus (menus, cutscenes).
