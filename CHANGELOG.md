# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Not applicable

### Changed
- Not applicable

### Removed
- Not applicable

## [1.2.14] - 2025-01-22
### Changed
- Added settings to launcher to allow data export without microG support
- Upgrade dependencies

## [1.2.13] - 2024-12-22
### Changed
- Fix wrong check breaking imports
- Extend blacklist
- Upgrade dependencies

## [1.2.12] - 2024-04-22
### Changed
- Extend blacklist
- Avoid crashes due to invalid emitter type
- Upgrade dependencies

## [1.2.11] - 2023-08-20
### Changed
- Import MLS / OpenCelliD lists without header

## [1.2.10] - 2023-05-22
### Changed
- Extended blacklist (thanks to Sorunome)
- Avoid searching nearby WiFis if GPS accuracy isn't good enough

## [1.2.9] - 2023-05-03
### Added
- Handle geo uris: allows adding emitters as if a GPS location was received at the indicated location

### Changed
- Improved blacklisting of unbelievably large emitters

## [1.2.8] - 2023-04-27
### Changed
- Fix potential import / export issue

## [1.2.7] - 2023-04-25
### Changed
- Crash fix
- Small UI changes when viewing nearby emitters and emitter details

## [1.2.6] - 2023-04-19
### Changed
- Notification text for active mode now contains name of emitter that triggered the scan
- Keep screen on during import / export operations

## [1.2.5] - 2023-02-10
### Changed
- Fix MLS import not working without MCC filter
- Support placeholder for simplified MCC filtering
- Fix bugs when importing files
- Clarify that OpenCelliD files can be used too, as the format is same as MLS
- Switch from Light theme to DayNight theme

## [1.2.4] - 2023-01-30
### Changed
- Fix not (properly) asking for background location, resulting in no location permissions being asked on Android 11+
- Update microG NLP API and other dependencies

## [1.2.3] - 2022-12-16
### Changed
- Extend blacklist
- Allow more aggressive active mode settings: fill the database better, but may increase battery use

## [1.2.2] - 2022-12-11
### Changed
- Different application id for debug builds
- Fix mobile emitters not being stored on some devices
- Improve storing/updating emitters, especially when using active mode
- Extend blacklist

## [1.2.2.beta.1] - 2022-10-11
### Added
- Support for 5G and TDSCDMA cells

### Changed
- Fix crashes
- Upgrade to API 33

## [1.2.1] - 2022-10-05
### Added
- Manually blacklist emitter when showing nearby emitters.
- Active mode: enable GPS when emitters are found, but none has a known location (disabled by default).

### Changed
- Update blacklist.

## [1.2.0] - 2022-09-25
### Changed
- Update blacklist.

## [1.2.0-beta.4] - 2022-09-13
### Changed
- Fix database import from content URI. Now import should work on all devices.

## [1.2.0-beta.3] - 2022-09-08
### Changed
- fix crash when showing nearby emitters
- slightly less ugly buttons when showing nearby emitters

## [1.2.0-beta.2] - 2022-09-07
### Added
- Progress bars for import and export

### Changed
- fix MLS import for LTE cells
- fix import of files exported with Local NLP Backend
- faster import
- reworked database code
- upgrade dependencies
- prepare for API upgrade (will remove deprecated getNeighboringCellInfo function, which may be used by some old devices)

## [1.2.0-beta] - 2022-09-07
### Added
- UI with capabilities to import/export emitters, show nearby emitters, select whether to use mobile cells and/or WiFi emitters, enable Kalman position filtering, and decide how to decide which emitters should be discarded in case of conflicting position reports.
- Blacklist emitters with suspiciously high radius, as they may actually be mobile hotspots.
- Don't use outdated WiFi scan results if scan is not successful. This helps especially against WiFi throttling introduced in Android 9. 
- Consider signal strength when estimating accuracy.

### Changed
- New app and package names.
- New icon (modified from: https://thenounproject.com/icon/38241).
- Some small bug fixes.
- Update and actually use the WiFi blacklist.
- Faster, but less exact distance calculations. For the used distances up to 100 km, the differences are negligible.
- Ignore cell emitters with invalid LAC.
- Try waiting until a WiFi scan is finished before reporting location. This avoids reporting a low accuracy mobile cell location followed by more precise WiFi-based location.
- Consider that LTE and 3G cells are usually smaller than GSM cells.
- Don't update emitters when GPS location and emitter timestamps differ by more than 10 seconds. This reduces issues with aggressive power saving functionality by Android.
- Adjusted how position and accuracy are determined.

### Removed
- Emitters will stay in the database forever, instead of being removed if not found in expected locations. In original *Déjà Vu*, many WiFi emitters are removed when they cannot be found for a while, e.g. because of thick walls. Having useless entries in the database is better than removing actually existing WiFis. Additionally this change reduces database writes and background processing considerably.
- Emitters will not be moved if they are found far away from their known location, as this mostly leads to bad location reports in connection with mobile hotspots. Instead they are blacklisted.

## [1.1.12] - 2019-08-12

### Changed
- Update gradle build environment.
- Add debug logging for detection of 5G WiFi/WLAN networds.
- Add some Czech, Austrian and Dutch transport WLANs to ignore list.

## [1.1.11] - 2019-04-21
### Added
- Add Esperanto and Polish translations

### Changed
- Update gradle build environment
- Revise list of WLAN/WiFi SSIDs to ignore

## [1.1.10] - 2018-12-18
### Added
- Ignore WLANs on trains and buses of transit agencies in southwest Sweden. Thanks to lbschenkel
- Ignore Austrian train WLANs. Thanks to akallabeth

### Changed
- Update Gradle build environment
- Revise checks for locations near lat/lon of 0,0

## [1.1.9] - 2018-09-06
### Added
- Chinese translation (thanks to @Crystal-RainSlide)
- Protect against external GPS giving locations near 0.0/0.0

## [1.1.8] - 2018-06-21
### Added
- Initial support for 5 GHz WLAN RF characteristics being different than 2.4 GHz WLAN. Note: 5GHz WLAN not tested on a real device.

### Changed
- Fix timing related crash on start up/shut down
- Revisions to better support external GPS with faster report rates.
- Revise database to allow same identifier on multiple emitter types.
- Updated build tools and target API version

## [1.1.7] - 2018-06-18
### Changed
- Fix crash on empty set of seen emitters.
- Fix some Lint identified items.

## [1.1.6] - 2018-06-17
### Added
- Add Ukrainian translation

### Changed
- Build specification to reduce size of released application.
- Update build environment

## [1.1.5] - 2018-03-19
### Added
- Russian Translation. Thanks to @bboa

## [1.1.4] - 2018-03-12
### Added
- German Translation. Thanks to @levush

## [1.1.3] - 2018-02-27

### Changed
- Protect against accessing null object.

## [1.1.2] - 2018-02-11

### Changed
- Fix typo in Polish strings. Thanks to @verdulo

## [1.1.1] - 2018-01-30
### Changed
- Refactor/clean up logic flow and position computation.

## [1.1.0] - 2018-01-25
### Changed
- Refactor RF emitter and database logic to allow for non-square coverage bounding boxes. Should result in more precise coverage mapping and thus better location estimation. Database file schema changed.

## [1.0.8] - 2018-01-12
### Added
- Polish Translation. Thanks to @verdulo

## [1.0.7] - 2018.01.05
### Changed
- Avoid crash on start up if database is not available when first RF emitter is processed.

## [1.0.6] - 2017-12-28
### Added
- French translation. Thanks to @Massedil.

## [1.0.5] - 2017-12-24
### Added
- Partial support for CDMA and WCDMA towers when using getAllCellInfo() API.

### Changed
- Check for unknown values in fields in the cell IDs returned by getAllCellInfo();

## [1.0.4] - 2017-12-18
### Changed
- Add more checks for permissions not granted to avoid locking up phone.

## [1.0.3]
### Changed
- Correct blacklist logic

## [1.0.2]
### Changed
- Correct versionCode and versionName in gradle.build

## [1.0.1]
### Changed
- Corrected package ID in manifest

## [1.0.0]
### Added
- Initial Release
