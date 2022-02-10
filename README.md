# Wasted

Lock a device and wipe its data on panic trigger.

[<img 
     src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.lucky.wasted/)
[<img 
      src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" 
      alt="Get it on Google Play" 
      height="80">](https://play.google.com/store/apps/details?id=me.lucky.wasted)

<img 
     src="https://raw.githubusercontent.com/x13a/Wasted/main/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" 
     width="30%" 
     height="30%">

You can use [PanicKit](https://guardianproject.info/code/panickit/), tile, shortcut or send a 
message with authentication code. On trigger, using 
[Device Administration API](https://developer.android.com/guide/topics/admin/device-admin), it 
locks a device and optionally runs wipe.

Also you can:
* limit the maximum number of failed password attempts
* wipe a device when it was not unlocked for N days

The app works in `Work Profile` too. Use [Shelter](https://github.com/PeterCxy/Shelter) to install 
risky apps and `Wasted` in it. Then you can wipe this profile data with one click without wiping 
the whole device.

Only encrypted device may guarantee that the data will not be recoverable.

## Permissions

* DEVICE_ADMIN           - lock and optionally wipe a device
* FOREGROUND_SERVICE     - receive unlock events
* RECEIVE_BOOT_COMPLETED - persist wipe job across reboots

## Example

Broadcast:
```sh
$ adb shell am broadcast \
    -a me.lucky.wasted.action.TRIGGER \
    -n me.lucky.wasted/.CodeReceiver \
    -e code "b49a6576-0c27-4f03-b96b-da53501022ba"
```

## Localization

Is Wasted not in your language, or the translation is incorrect or incomplete? Get involved on 
[Crowdin](https://crwd.in/me-lucky-wasted).



## License
[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)  

This application is Free Software: You can use, study share and improve it at your will. 
Specifically you can redistribute and/or modify it under the terms of the
[GNU General Public License v3](https://www.gnu.org/licenses/gpl.html) as published by the Free 
Software Foundation.
