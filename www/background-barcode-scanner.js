var exec = require('cordova/exec');

// The native implementations should return their status as ['string':'string']
// dictionaries. Boolean values are encoded to '0' and '1', respectively.
function stringToBool(string) {
	switch (string) {
		case '1':
			return true;
		case '0':
			return false;
		default:
			throw new Error('BBScanner plugin returned an invalid boolean number-string: ' + string);
	}
}

// Simple utility method to ensure the background is transparent. Used by the
// plugin to force re-rendering immediately after the native webview background
// is made transparent.
function clearBackground() {
	window.requestAnimationFrame(function(){
		var body = document.body;
		if (body.style) {
			body.style.backgroundColor = 'rgba(0,0,0,0.01)';
			body.style.backgroundImage = '';
			window.requestAnimationFrame(function(){
				body.style.backgroundColor = 'transparent';
			});
			if (body.parentNode && body.parentNode.style) {
				body.parentNode.style.backgroundColor = 'transparent';
				body.parentNode.style.backgroundImage = '';
			}
		}
	});
}

// Converts the returned ['string':'string'] dictionary to a status object.
function convertStatus(statusDictionary) {
	return {
		authorized: stringToBool(statusDictionary.authorized),
		denied: stringToBool(statusDictionary.denied),
		restricted: stringToBool(statusDictionary.restricted),
		prepared: stringToBool(statusDictionary.prepared),
		scanning: stringToBool(statusDictionary.scanning),
		previewing: stringToBool(statusDictionary.previewing),
		showing: stringToBool(statusDictionary.showing),
		lightEnabled: stringToBool(statusDictionary.lightEnabled),
		canOpenSettings: stringToBool(statusDictionary.canOpenSettings),
		canEnableLight: stringToBool(statusDictionary.canEnableLight),
		canChangeCamera: stringToBool(statusDictionary.canChangeCamera),
		currentCamera: parseInt(statusDictionary.currentCamera)
	};
}

// Error callback
function errorCallback(callback) {
	if ( !callback || typeof callback !== 'function')
		return null;

	return function(error) {
		var errorCode = parseInt(error);
		var ScannerError = {};
		switch (errorCode) {
			case 0:
				ScannerError = {
					name: 'UNEXPECTED_ERROR',
					code: 0,
					_message: 'BBScanner experienced an unexpected error.'
				};
			break;
			case 1:
				ScannerError = {
					name: 'CAMERA_ACCESS_DENIED',
					code: 1,
					_message: 'The user denied camera access.'
				};
			break;
			case 2:
				// Only for Android 6.0+.
				ScannerError = {
					name: 'CAMERA_ACCESS_PERMANENT_DENIED',
					code: 2,
					_message: 'Camera access is permanent denied.'
				};
			break;
			case 3:
				ScannerError = {
					name: 'BACK_CAMERA_UNAVAILABLE',
					code: 3,
					_message: 'The back camera is unavailable.'
				};
			break;
			case 4:
				ScannerError = {
					name: 'FRONT_CAMERA_UNAVAILABLE',
					code: 4,
					_message: 'The front camera is unavailable.'
				};
			break;
			case 5:
				ScannerError = {
					name: 'CAMERA_UNAVAILABLE',
					code: 5,
					_message: 'The camera is unavailable.'
				};
			break;
			case 6:
				ScannerError = {
					name: 'SCAN_CANCELED',
					code: 6,
					_message: 'Scan was canceled.'
				};
			break;
			case 7:
				ScannerError = {
					name: 'LIGHT_UNAVAILABLE',
					code: 7,
					_message: 'The device light is unavailable.'
				};
			break;
			case 8:
				// Open settings is only available on iOS 8.0+.
				ScannerError = {
					name: 'OPEN_SETTINGS_UNAVAILABLE',
					code: 8,
					_message: 'The device is unable to open settings.'
				};
			break;
			case 9:
				// Only for iOS.
				ScannerError = {
					name: 'CAMERA_ACCESS_RESTRICTED',
					code: 8,
					_message: 'The camera is restricted.'
				};
			break;
			default:
				ScannerError = {
					name: 'UNEXPECTED_ERROR',
					code: 0,
					_message: 'BBScanner returned an invalid error code.'
				};
			break;
		}
		callback(ScannerError);
	};
}

// Success callback
function successCallback(callback) {
	if ( !callback || typeof callback !== 'function')
		return null;

	return function(statusDict) {
		callback(null, convertStatus(statusDict));
	};
}

// Done callback
function doneCallback(callback, clear) {
	if ( !callback || typeof callback !== 'function')
		callback = function(){};

	return function(statusDict) {
		if (clear) {
			clearBackground();
		}
		callback(convertStatus(statusDict));
	};
}

exports.show = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'show', []);
}

exports.hide = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'hide', []);
}

exports.scan = function(options, callback) {
	if ( typeof options == 'function' ){
		callback = options;
		options  = {};
	}
	if (!callback) {
		throw new Error('No callback provided to scan method.');
	}
	var success = function(result) {
		callback(null, result);
	};
	exec(success, errorCallback(callback), 'BBScanner', 'scan', [options]);
}

exports.pause = function(callback) {
	if ( !callback || typeof callback !== 'function') {
		throw new Error('No callback provided to pause method.');
	}
	exec(callback, null, 'BBScanner', 'pause', []);
}

exports.resume = function(callback) {
	if ( !callback || typeof callback !== 'function') {
		throw new Error('No callback provided to resume method.');
	}
	exec(callback, null, 'BBScanner', 'resume', []);
}

exports.snap = function(callback) {
	if ( !callback || typeof callback !== 'function') {
		throw new Error('No callback provided to snap method.');
	}
	exec(callback, null, 'BBScanner', 'snap', []);
}

exports.stop = function(callback) {
	exec(doneCallback(callback), null, 'BBScanner', 'stop', []);
}

exports.openSettings = function(callback) {
	if ( callback && typeof callback == 'function') {
		exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'openSettings', []);
	}else{
		exec(null, null, 'BBScanner', 'openSettings', []);
	}
}

exports.pausePreview = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'pausePreview', []);
}

exports.resumePreview = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'resumePreview', []);
}

exports.switchCamera = function(index, callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'switchCamera', [index]);
}

exports.enableLight = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'enableLight', []);
}

exports.disableLight = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'disableLight', []);
}

exports.useFrontCamera = function(callback) {
	var frontCamera = 1;
	if (callback) {
		this.switchCamera(frontCamera, callback);
	} else {
		exec(null, null, 'BBScanner', 'switchCamera', [frontCamera]);
	}
}

exports.useBackCamera = function(callback) {
	var backCamera = 0;
	if (callback) {
		this.switchCamera(backCamera, callback);
	} else {
		exec(null, null, 'BBScanner', 'switchCamera', [backCamera]);
	}
}

exports.prepare = function(callback) {
	exec(successCallback(callback), errorCallback(callback), 'BBScanner', 'prepare', []);
}

exports.destroy = function(callback) {
	exec(doneCallback(callback, true), null, 'BBScanner', 'destroy', []);
}

exports.getStatus = function(callback) {
	if ( !callback || typeof callback !== 'function') {
		throw new Error('No callback provided to getStatus method.');
	}
	exec(doneCallback(callback), null, 'BBScanner', 'getStatus', []);
}

exports.types = {
	"AZTEC": "AZTEC",
	"CODABAR": "CODABAR",
	"CODE_39": "CODE_39",
	"CODE_93": "CODE_93",
	"CODE_128": "CODE_128",
	"DATA_MATRIX": "DATA_MATRIX",
	"EAN_8": "EAN_8",
	"EAN_13": "EAN_13",
	"ITF": "ITF",
	"PDF417": "PDF417",
	"QR_CODE": "QR_CODE",
	"RSS_14": "RSS_14",
	"RSS_EXPANDED": "RSS_EXPANDED",
	"UPC_A": "UPC_A",
	"UPC_E": "UPC_E",
	"UPC_EAN_EXTENSION": "UPC_EAN_EXTENSION"
}
