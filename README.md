# Wasted

Wipe data on panic trigger.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
     alt="Get it on F-Droid"
     height="80">](https://f-droid.org/packages/me.lucky.wasted/)

<img src="https://user-images.githubusercontent.com/53379023/142707625-1c8a90e2-3254-4660-9635-fcc55056508a.png" width="30%" height="30%">

The app will listen for broadcast message with authentication code. On receive, using 
[Device Administration API](https://developer.android.com/guide/topics/admin/device-admin), it 
locks device and runs wipe.

## Example

Broadcast message:
```sh
$ adb shell am broadcast \
    -a me.lucky.wasted.action.ESCAPE \
    -n me.lucky.wasted/.ControlReceiver \
    -e code "b49a6576-0c27-4f03-b96b-da53501022ba"
```

## License
[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)  

This application is Free Software: You can use, study share and improve it at your
will. Specifically you can redistribute and/or modify it under the terms of the
[GNU General Public License v3](https://www.gnu.org/licenses/gpl.html) as
published by the Free Software Foundation.
