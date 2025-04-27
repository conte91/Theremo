# Theremo companion app for the Moog Theremini

This small Android app provides full control of the Moog Theremini parameters, adjustable via the Theremini's USB (MIDI) port.

A lot of the Theremini's sounds (especially filter settings) are only accessible from the Moog's MIDI port, which is fortunately well documented in [the manual](https://api.moogmusic.com/sites/default/files/2018-09/Theremini_Manual_6_25.pdf). While Moog provides companion apps for Windows and iOS, there is no support for Android or non-Windows devices. Furthermore, accessing either app requires registering the Theremini on Moog's website.

This app provides all available controls organized in tabs. It also provides the ability to store and load presets - these are stored _on the Android device_ and not directly on the Theremini.

## Installation

Find the latest app version in the [releases page](https://github.com/conte91/Theremo/releases). Download and install the APK file on your Android device.

Upcoming (maybe?) on Google Play Store and F-Droid?

## Building from source

Clone the repo, then run `./gradlew assembleDebug`.

Install the APK on your phone with: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

For building the release APK, you'll need to setup a valid APK signing keystore. Create a `keystore.properties` file in the root directory with the following content:

```
keyAlias = your_keystore_key_alias
keyPassword = your\ signing\ key\ password
storeFile = /path/to/your.keystore
storePassword = YourKeystorePassword
```

Now you can `./gradlew assembleRelease`.


## Known issues

### Man, is this ugly!

I know :D But it's also usable.

UX improvements are welcome :)

### Saving a preset only stores values which have been tweaked.

This is an inherent issue with how the Theremini/MIDI connection works. AFAIK, the Theremini doesn't provide a way to query for current parameters via MIDI, only to set them. Which means the app has no way of knowing what the values of a parameter are until they're explicitly set in the app.

I recommend that before saving their first preset, one goes through _all_ knobs and sets them to a nominal value (use the "DEFAULT" button as needed). That will make sure that all values are restored when a preset is loaded.

### I want to contribute!

Sure, go ahead :) Feel free to send me PRs; keep in mind that I will feel free to only look at them when I have some spare time.
