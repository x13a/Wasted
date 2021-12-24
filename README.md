# Wasted

Lock device and wipe data on panic trigger.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.lucky.wasted/)

<img src="https://user-images.githubusercontent.com/53379023/146694310-41316fdb-b7c7-44e7-b18d-a1f4d0a7bec5.png" width="30%" height="30%">

You can use [PanicKit](https://guardianproject.info/code/panickit/), Tile or send broadcast message 
with authentication code. On trigger, using 
[Device Administration API](https://developer.android.com/guide/topics/admin/device-admin), it 
locks device and (optionally) runs wipe.

If you are looking something for computer try [wanted](https://github.com/x13a/wanted).

## Example

Broadcast message:
```sh
$ adb shell am broadcast \
    -a me.lucky.wasted.action.TRIGGER \
    -n me.lucky.wasted/.CodeReceiver \
    -e code "b49a6576-0c27-4f03-b96b-da53501022ba"
```

## License
[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)  

This application is Free Software: You can use, study share and improve it at your
will. Specifically you can redistribute and/or modify it under the terms of the
[GNU General Public License v3](https://www.gnu.org/licenses/gpl.html) as
published by the Free Software Foundation.
