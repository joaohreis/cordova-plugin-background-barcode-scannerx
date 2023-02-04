# cordova-plugin-multiple-barcode-scan

A fork of the plugin [cordova-plugin-background-barcode-scanner](https://github.com/vash15/cordova-plugin-background-barcode-scanner), followed by a refactoring of the source code to improve its performance and maintainability. To finish, the README file was updated with accurate and detailed information on the use of the plugin after the refactoring. The plugin was refactored with a focus on Android 19 and higher, without androidX support.


This plugin started based on **Bitpay**'s [QRScanner](https://github.com/bitpay/cordova-plugin-qrscanner). I needed to use a barcode scanner under the webview and I modified the original plugin to do this.


A fast, energy efficient, highly-configurable QR code scanner for Cordova apps – available for the iOS, Android, Windows, and browser platforms.

BBScanner's native camera preview is rendered behind the Cordova app's webview, and BBScanner provides `show` and `hide` methods to toggle the transparency of the webview's background. This allows for a completely HTML/CSS/JS interface to be built inside the webview to control the scanner.

## Examples

<!-- Does your project use cordova-plugin-qrscanner? We'd love to share a screenshot of your scanning interface! Please send a pull request adding your screenshot to the list below. -->

<table>
<tr align="center">
<!-- Please be sure your screenshot is hosted by cloud.githubusercontent.com. (You can upload by adding the image to any GitHub issue. -->
<td><img height="450" src="https://cloud.githubusercontent.com/assets/904007/24809138/943a9628-1b8c-11e7-8659-828c8060a9b6.PNG" alt="BitPay – Secure Bitcoin Wallet"></td>
<td><img height="450" src="https://cloud.githubusercontent.com/assets/904007/24809499/b192a246-1b8d-11e7-9f3b-e85ae480fdd6.PNG" alt="Copay Bitcoin Wallet Platform"></td>
<td><img height="450" src="https://cloud.githubusercontent.com/assets/5379359/25655918/0909bac8-2ff7-11e7-8775-ebb11bb085d6.png" alt="BitPocket Point Of Sale App"></td>
</tr>
<tr align="center">
<!-- Please provide a title and, if possible, a link to your project. -->
<td><a href="https://bitpay.com/wallet">BitPay – Secure Bitcoin Wallet</a></td>
<td><a href="https://github.com/bitpay/copay">bitpay/copay</a></td>
<td><a href="https://github.com/getbitpocket/bitpocket-mobile-app">BitPocket - Bitcoin Point of Sale App</a></td>
</tr>
</table>

## Get Started

```bash
cordova plugin add https://github.com/Willian199/cordova-plugin-multiple-barcode-scan
```

Simply adding this plugin to the Cordova project will make the `cordova.plugins.BBScanner` global object available once the `deviceready` event propagates.

### Usage

There are two primary steps to integrating `cordova-plugin-multiple-barcode-scan`.

#### 1. Get Permission Early (Optional)

**This step is optional, but very recomended** – if the best place for your app to ask for camera permissions is at the moment scanning begins, you can safely skip this step.

If there's a better place in your app's onboarding process to ask for permission to use the camera ("permission priming"), this plugin makes it possible to ask prior to scanning using the [`prepare` method](#prepare). The `prepare` method initializes all the infrastructure required for scanning to happen, including (if applicable) asking for camera permissions. This can also be done before attempting to show the video preview, making your app feel faster and more responsive.

```js
// For the best user experience, make sure the user is ready to give your app
// camera access before you show the prompt. On iOS, you only get one chance.

BBScanner.prepare(onDone); // show the prompt

function onDone(err, status){
  if (err) {
   // here we can handle errors and clean up any loose ends.
   console.error(err);
  }
  //Will return the result from BBScanner.getStatus()
  if (status.authorized) {
    // You have camera access and the scanner is initialized.
    // BBScanner.show() should feel very fast.
  } else if (status.denied) {
   // The video preview will remain black, and scanning is disabled. We can
   // try to ask the user to change their mind, but we'll have to send them
   // to their device settings with `BBScanner.openSettings()`.
  } else {
    // we didn't get permission, but we didn't get permanently denied. (On
    // Android, a denial isn't permanent unless the user checks the "Don't
    // ask again" box.) We can ask again at the next relevant opportunity.
  }
}
```

#### 2. Scan

Later in your application, simply call the [`scan` method](#scan) to enable scanning.

If you haven't previously `prepare`d the scanner, the `scan` method will first internally `prepare` the scanner, then begin scanning. If you'd rather ask for camera permissions at the time scanning is attempted, this is the simplest option.

Remeber, make the webview transparent.

```js
// Start a scan. Scanning will continue until something is detected or
// `BBScanner.stop()` is called.
BBScanner.scan({format: cordova.plugins.BBScanner.types.QR_CODE, multipleScan: false},displayContents);

function displayContents(err, text){
  if(err){
    // an error occurred, or the scan was canceled (error code `6`)
  } else {
    // The scan completed, display the contents of the QR code:
    alert(text);
  }
}

```

## API
Some functions have required callbacks while others have optional ones. To view the specification of each, refer to the documentation.

### Prepare

```js
var done = function(err, status){
  if(err){
    console.error(err._message);
  } else {
    console.log('QRScanner is initialized. Status:');
    console.log(status);
  }
};

BBScanner.prepare(done);
```

Request permission to access the camera (if not already granted), prepare the video preview, and configure everything needed by BBScanner. On platforms where possible, this also starts the video preview, saving valuable milliseconds and making it seem like the camera is starting instantly when `BBScanner.scan()` is called. (These changes will only be visible to the user if `BBScanner.scan()` has already made the webview transparent.)

The `callback` is required.

### Scan

```js
var callback = function(err, contents){
  if(err){
    console.error(err._message);
  }
  alert('The QR Code contains: ' + contents);
};

BBScanner.scan({format: cordova.plugins.BBScanner.types.QR_CODE, multipleScan: false}, callback);
```

Sets QRScanner to "watch" for valid QR codes. Once a valid code is detected, it's contents are passed to the callback, and scanning is toggled off. If `BBScanner.prepare()` has not been called, this method performs that setup as well. On platforms other than iOS and Android, the video preview must be visible for scanning to function.

With the `multipleScan` option is it possibile to retrieve more than one barcode per scan calls, the preview is not stopped. Be aware of the sequential scans, combine it with `pause` and `resume` for the best user experience.
If false, will retrive the barcode and call [`destroy`](#destroy) internally.

The `format` variable is optional and will accept only one format. Default: will accept any format.

The `callback` is required.

```js
BBScanner.stop(function(status){
  console.log(status);
});
```

Cancels the current scan. When a scan is canceled, the callback of the canceled `scan()` receives the `SCAN_CANCELED` error.

### Show

```js
BBScanner.show(function(status){
  console.log(status);
});
```

Configures the native webview to have a transparent background, then sets the background of the `<body>` and `<html>` DOM elements to transparent, allowing the webview to re-render with the transparent background.

To see the video preview, your application background must be transparent in the areas through which the preview should show.

The [`show`](#show) and [`hide`](#hide) methods are the fastest way to toggle visibility of the scanner. When building the scanner into tab systems and similar layouts, this makes the application feel much more responsive. It's possible to reduce power consumption (to extend battery life on mobile platforms) by intellegently [`destroy`](#destroy)ing the scanner when it's unlikely to be used for a long period of time. Before scanning is used again, you can re-[`prepare`](#prepare) it, making the interface seem much more responsive when `show` is called.

### Hide

```js
BBScanner.hide(function(status){
  console.log(status);
});
```

Configures the native webview to be opaque with a white background, covering the video preview.

### Lighting

```js
BBScanner.enableLight(function(err, status){
  err && console.error(err);
  console.log(status);
});
```

Enable the device's light (for scanning in low-light environments). If `BBScanner.prepare()` has not been called, this method will throw `CAMERA_ACCESS_DENIED`.

```js
BBScanner.disableLight(function(err, status){
  err && console.error(err);
  console.log(status);
});
```

Disable the device's light. If `BBScanner.prepare()` has not been called, this method will throw `CAMERA_ACCESS_DENIED`.

### Switch Camera
QRScanner defaults to the back camera, but can be reversed. If `BBScanner.prepare()` has not been called, this method will throw `CAMERA_ACCESS_DENIED`.

If the front camera is unavailable, this method will throw `FRONT_CAMERA_UNAVAILABLE`.

Switch video capture to the device's front camera.

```js
BBScanner.useFrontCamera(function(err, status){
  err && console.error(err);
  console.log(status);
});
```

Switch video capture to the device's back camera.

```js
BBScanner.useBackCamera(function(err, status){
  err && console.error(err);
  console.log(status);
});
```

Camera selection can also be done directly with the `switchCamera` method. 
Camera identification is optional, will automatically reverse when not passed.

```js
var back = 0; // default camera on plugin initialization
var front = 1;
BBScanner.switchCamera(front, function(err, status){
  err && console.error(err);
  console.log(status);
});
```


### Video Preview Control

```js
BBScanner.pausePreview(function(status){
  console.log(status);
})
```

Pauses the video preview on the current frame (as if a snapshot was taken) and pauses scanning (if a scan is in progress).

```js
BBScanner.resumePreview(function(status){
  console.log(status);
})
```

Resumes the video preview and continues to scan (if a scan was in progress before `pausePreview()`).


### Snap

Creates a snapshot of the current camera preview and returns it in base64 format.

**Attention! On Android the scan mode should be paused before call `snap` or the image won't be returned**.

Use `pause` method to stop barcode scanning keeping the camera preview active and then `resume` to reactivate the scan.

### Pause scan

Call the `pause` method to stop the scan keeping the camera preview active. Call `resume` to reactivate the scan.

### Resume scan

Call `resume` method to reactivate the scan process. If scan is already active it has no effect.


### Open App Settings

```js
BBScanner.getStatus(function(status){
  if(!status.authorized && status.canOpenSettings){
    if(confirm("Would you like to enable QR code scanning? You can allow camera access in your settings.")){
      BBScanner.openSettings();
    }
  }
});
```

Open the app-specific permission settings in the user's device settings. Here the user can enable/disable camera (and other) access for your app.

Note: iOS immediately kills all apps affected by permission changes in Settings. If the user changes a permission setting, your app will stop and only restart when they return.

### Get QRScanner Status

```js
BBScanner.getStatus(function(status){
  console.log(status);
});
```

```js
{
  "authorized": Boolean
  "denied": Boolean
  "restricted": Boolean
  "prepared": Boolean
  "scanning": Boolean
  "previewing": Boolean
  "showing": Boolean
  "lightEnabled": Boolean
  "canOpenSettings": Boolean
  "canEnableLight": Boolean
  "currentCamera": Number
}
```

Retrieve the status of QRScanner and provide it to the callback function.

### Status Object Properties

Name                             | Description
:------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
`authorized`                     | On iOS and Android 6.0+, camera access is granted at runtime by the user (by clicking "Allow" at the dialog). The `authorized` property is a boolean value which is true only when the user has allowed camera access to your app (`AVAuthorizationStatus.Authorized`). On platforms with permissions granted at install (Android pre-6.0, Windows Phone) this property is always true.
`denied`                         | A boolean value which is true if the user permanently denied camera access to the app (`AVAuthorizationStatus.Denied`). Once denied, camera access can only be gained by requesting the user change their decision (consider offering a link to the setting via `openSettings()`).
`restricted`                     | A boolean value which is true if the user is unable to grant permissions due to parental controls, organization security configuration profiles, or similar reasons. Only for iOS, on android always will be false.
`prepared`                       | A boolean value which is true if BBScanner is prepared to capture video and render it to the view.
`showing`                        | A boolean value which is true when the preview layer is visible (and on all platforms but `browser`, the native webview background is transparent).
`scanning`                       | A boolean value which is true if BBScanner is actively scanning for a QR code.
`previewing`                     | A boolean value which is true if BBScanner is displaying a live preview from the device's camera. Set to false when the preview is paused.
`lightEnabled`                   | A boolean value which is true if the light is enabled.
`canOpenSettings`                | A boolean value which is true only if the users' operating system is able to `BBScanner.openSettings()`.
`canEnableLight`                 | A boolean value which is true only if the users' device can enable a light in the direction of the currentCamera.
`canChangeCamera`                | A boolean value which is true only if the current device "should" have a front camera. The camera may still not be capturable, which would emit error code 3, 4, or 5 when the switch is attempted.
`currentCamera`                  | A number representing the index of the currentCamera. `0` is the back camera, `1` is the front.


### Destroy

```js
BBScanner.destroy(function(status){
  console.log(status);
});
```

Runs [`hide`](#hide), [`stop`](#stop), stops video capture, removes the video preview, disable flash and deallocates as much as possible. Basically reverts the plugin to it's startup-state.

## Error Handling
Many BBScanner functions accept a callback with an `error` parameter. When QRScanner experiences errors, this parameter contains a QRScannerError object with properties `name` (_String_), `code` (_Number_), and `_message` (_String_). When handling errors, rely only on the `name` or `code` parameter, as the specific content of `_message` is not considered part of the plugin's stable API. Particularly if your app is localized, it's also a good idea to provide your own `message` when informing the user of errors.

```js
BBScanner.scan(function(err, contents){
  if(err){
    if(err.name === 'SCAN_CANCELED') {
      console.error('The scan was canceled before a QR code was found.');
    } else {
      console.error(err._message);
    }
  }
  console.log('Scan returned: ' + contents);
});
```

### Possible Error Types

Code | Name                        | Description
---: | :-------------------------- | :-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
   0 | `UNEXPECTED_ERROR`          | An unexpected error. Returned only by bugs in BBScanner.
   1 | `CAMERA_ACCESS_DENIED`      | The user denied camera access.
   2 | `CAMERA_ACCESS_PERMANENT_DENIED`  | The user permanent denied camera access. Only for android 6.0+.
   3 | `BACK_CAMERA_UNAVAILABLE`   | The back camera is unavailable.
   4 | `FRONT_CAMERA_UNAVAILABLE`  | The front camera is unavailable.
   5 | `CAMERA_UNAVAILABLE`        | The camera is unavailable because it doesn't exist or is otherwise unable to be configured. (Also returned if QRScanner cannot return one of the more specific `BACK_CAMERA_UNAVAILABLE` or `FRONT_CAMERA_UNAVAILABLE` errors.)
   6 | `SCAN_CANCELED`             | Scan was canceled by the `cancelScan()` method. (Returned exclusively to the `BBScanner.scan()` method.)
   7 | `LIGHT_UNAVAILABLE`         | The device light is unavailable because it doesn't exist or is otherwise unable to be configured.
   8 | `OPEN_SETTINGS_UNAVAILABLE` | The device is unable to open settings.
   9 | `CAMERA_ACCESS_RESTRICTED`  | Camera access is restricted (due to parental controls, organization security configuration profiles, or similar reasons). Only for iOS

## Platform Specific Details

This plugin attempts to properly abstract all the necessary functions of a well-designed, native QR code scanner. Here are some platform specific details it may be helpful to know.

## iOS

This plugin is always tested with the latest version of Xcode. Please be sure you have updated Xcode before installing.

## Android

On Android, calling `pausePreview()` will also disable the light. However, if `disableLight()` is not called, the light will be reenabled when `resumePreview()` is called.

### Permissions

Unlike iOS, on Android >=6.0, permissions can be requested multiple times. If the user denies camera access, `status.denied` will remain `false` unless the user permanently denies by checking the `Never ask again` checkbox. Once `status.denied` is `true`, `openSettings()` is the only remaining option to grant camera permissions.

Because of API limitations, `status.restricted` will always be false on the Android platform. See [#15](https://github.com/bitpay/cordova-plugin-qrscanner/issues/15) for details. Pull requests welcome!


### Privacy Lights

Most devices now include a hardware-level "privacy light", which is enabled when the camera is being used. To prevent this light from being "always on" when the app is running, the browser platform disables/enables use of the camera with the `hide`, `show`, `pausePreview`, and `resumePreview` methods.


## Typescript
Type definitions for cordova-plugin-qrscanner are [available in the DefinitelyTyped project](https://github.com/DefinitelyTyped/DefinitelyTyped/blob/master/cordova-plugin-qrscanner/cordova-plugin-qrscanner.d.ts).

## Contributing &amp; Testing

To contribute, first install the dependencies:

```sh
npm install
```

Then setup the test project:

```sh
npm run gen-tests
```

This will create a new cordova project in the `cordova-plugin-test-projects` directory next to this repo, install `cordova-plugin-qrscanner`, and configure the [Cordova Plugin Test Framework](https://github.com/apache/cordova-plugin-test-framework). Once the platform tests are generated, the following commands are available:

- `npm run test:android`
- `npm run test:browser`
- `npm run test:ios`
- `npm run test:windows`

Both Automatic Tests (via Cordova Plugin Test Framework's built-in [Jasmine](https://github.com/jasmine/jasmine)) and Manual Tests are available. Automatic tests confirm the existence and expected structure of the javascript API, and manual tests should be used to confirm functionality on each platform.

The manual tests for the library are available without the cordova test project:

- `npm run test:library`

The build for this repo currently only confirms javascript style and syntax with [jshint](https://github.com/jshint/jshint). Pull requests with additional automated test methods are welcome!
