# Motion cues

Motion cues is an Android app that shows an overlay while you use your phone in a car, bus, or train.

You need to activate the overlay yourself, it is not automatic.

It works similarly to vehicle motion cues on iPhone and aims to reduce motion sickness by drawing dots that drift with the vehicle's motion.

Shifts to make it as close to Apple's implementation as possible:
- Stopped adjusting the dot grid to be parallel to the earth.
- Dots are now static regardless of the phone tilting left/right or up/down. It will only react to acceleration.
- Dots cannot move up/down anymore. They will only move left/right regardless of phone orientation or vertical acceleration.

## Video of Working App

<video src="screen_recording.mp4" width="320" height="240" controls></video>

## ACKNOWLEDGEMENTS

Forked from: https://github.com/DavidVentura/motion-sickness-app
With quick tile feature from: https://github.com/jaival-11/motion-sickness-app

## License

This project is licensed under `GPL-3.0-only`.

See [LICENSE](LICENSE) for the full text.
