Note that microG has stopped supporting UnifiedNlp backends with 0.2.28.

If you still want to use this backend (or others), you need to use older microG versions. This can only be recommended if you use microG __for location only__.

Personally I use [0.2.10](https://github.com/microg/GmsCore/releases/tag/v0.2.10.19420), as with later versions location backends stop providing locations after some time.

Local NLP Backend - A Déjà Vu Fork
==================================
This is a backend for [UnifiedNlp](https://github.com/microg/android_packages_apps_UnifiedNlp) that uses locally acquired WLAN/WiFi AP and mobile/cellular tower data to resolve user location. Collectively, “WLAN/WiFi and mobile/cellular” signals will be called “RF emitters” below.

Conceptually, this backend consists of two parts sharing a common database. One part passively monitors the GPS. If the GPS has acquired a position and has good position accuracy, the coverage maps for RF emitters detected by the phone are created and saved.

The other part is the actual location provider which uses the database to estimate the location when the GPS is not available.
This backend uses no network data. All data acquired by the phone stays on the phone.

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/helium314.localbackend/)
[<img src="https://user-images.githubusercontent.com/663460/26973090-f8fdc986-4d14-11e7-995a-e7c5e79ed925.png" alt="Download APK from GitHub" height="80">](https://github.com/Helium314/Local-NLP-Backend/releases/latest)

Note that F-Droid and GitHub releases use a different signing key. You cannot switch from one to the other without uninstalling Local NLP Backend first. However, you can always install the debug version (only available on GitHub) in addition to the normal version.

See the [changelog](CHANGELOG.md) starting at 1.2.0-beta for a full list of changes starting from the last version of *Déjà Vu*.

How to use
==========
Local NLP Backend can be used like *Déjà Vu*: just enable the backend and let it build up the database by frequently having GPS enabled, e.g. using a map app.
If you have a *Déjà Vu* database (you'll need root privileges to extract it), it can be imported in Local NLP Backend. Further import options are databases exported by Local NLP Backend, and cell csv files from MLS or OpenCelliD.
Note that the local database needs to be filled either using GPS or by importing data, before Local NLP Backend can provide locations!

In order to speed up building the database, Local NLP Backend has an optional active mode that enables GPS when there is no known emitter nearby (low setting) or when any unknown emitter is found (aggressive setting).
If you have a bad GPS signal at a location, you can share a location using geo uri to Local NLP Backend, e.g. using OSMAnd share -> "geo:" or StreetComplete "open location in another app". This will cause Local NLP Backend to act as if a GPS location was received at the indicated location, and allows you to manually build a database even without GPS.

On [some Android versions](https://developer.android.com/guide/topics/connectivity/wifi-scan#wifi-scan-throttling), the ability to perform WiFi scans is severely limited. Local NLP Backend does not have control over this, and is limited by the specified background app limit.

Potential improvements not yet implemented
======================
Local NLP Backend works mostly fine as it is, but there are some areas where it could be improved:
* characteristics for the various different emitters are roughly estimated from various sources on the internet. Fine tuning of the values might improve location accuracy, especially when also considering frequency effects on range.
* make use of bluetooth emitters. Bluetooth has low range and thus a good potential of giving accurate locations, but is difficult to use properly as many bluetooth emitters are mobile.
* make use of [WiFi-RTT](https://developer.android.com/guide/topics/connectivity/wifi-rtt) for distance estimation. This has the potential to vastly improve precision, but works only on a small number of devices.
* determination of position from found emitters is just working "good enough", but not great. A different approach might yield better results.
* country code filtering in cell import currently requires lookup of the codes from some other source, this could be improved to allow for simply entering chosen countries instead.

Requirements on phone
=====================
This is a plug-in for [microG](https://microg.org/) (UnifiedNlp or GmsCore).

Setup on phone
==============
In the NLP Controller app (interface for microG UnifiedNlp) select the "Local NLP Backend". If using GmsCore, you can find it in microG Settings -> Location modules. Tap on backend name for the configuration UI.

When enabled, microG will request you grant location permissions to this backend. This is required so that the backend can monitor mobile/cell tower data and so that it can monitor the positions reported by the GPS.

Note: The microG configuration check requires a location from a location backend to indicate that it is setup properly. However, this backend will not return a location until it has mapped at least one mobile cell tower or two WLAN/WiFi access points, or data was imported. So it may be necessary to run an app that uses the GPS for a while before this backend will report information to microG. You may wish to also install a different backend to verify microG setup quickly.

Collecting RF Emitter Data
======================
To conserve power the collection process by default does not actually turn on the GPS. If some other app turns on the GPS, for example a map or navigation app, then the backend will monitor the location and collect RF emitter data.
Alternatively you can enable active mode in the settings available via microG backend configuration.

What is stored in the database
------------------------------
For each RF emitter detected an estimate of its coverage area (center and extents) is saved.

For WLAN/WiFi APs the SSID is also saved for debug purposes. Analysis of the SSIDs detected by the phone can help identify name patterns used on mobile APs. The backend removes records from the database if the RF emitter has a SSID that is associated with WLAN/WiFi APs that are often mobile (e.g. "Joes iPhone").

Clearing the database
---------------------
This software does not have a clear or reset database function built in but you can use settings->Storage->Internal shared storage->Apps->Local NLP Backend->Clear Data to remove the current database.

Permissions Required
====================
|Permission|Use|
|:----------|:---|
ACCESS_COARSE_LOCATION|Allows backend to determine which cell towers your phone detects.
ACCESS_FINE_LOCATION|Allows backend to determine which WiFis your phone detect and monitor position reports from the GPS.
ACCESS_BACKGROUND_LOCATION|Necessary on Android 10 and higher, as the backend only runs in foreground when using active mode.
CHANGE_WIFI_STATE|Allows backend to scan for nearby WiFis.
ACCESS_WIFI_STATE|Allows backend to access WiFi scan results.
FOREGROUND_SERVICE|Needed so GPS can be used in active mode.

Some permissions may not be necessary, this heavily depends on the [Android version](https://developer.android.com/guide/topics/connectivity/wifi-scan).

Changes
=======
[Revision history is kept in a separate change log.](CHANGELOG.md)

Credits
=======
The Kalman filter used in this backend is based on [work by @villoren](https://github.com/villoren/KalmanLocationManager.git).

Most of this project is adjusted from [Déjà Vu](https://github.com/n76/DejaVu)

License
=======

Most of this project is licensed by GNU GPL. The Kalman filter code retains its original MIT license.

Icon
----
The icon for this project is derived from:

[Geolocation icon](https://commons.wikimedia.org/wiki/File:Geolocation_-_The_Noun_Project.svg) released under [CC0 license](https://creativecommons.org/publicdomain/zero/1.0/deed.en).

GNU General Public License
--------------------------
Copyright (C) 2017-18 Tod Fitch
Copyright (C) 2022-23 Helium314

This program is Free Software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License

MIT License
-----------
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
