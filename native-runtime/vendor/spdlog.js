// VSCodroid: pure-JavaScript stand-in for the @vscode/spdlog native addon.
//
// Installed by scripts/build-native-addons.sh, which also deletes
// build/Release/spdlog.node. Do not restore the published index.js: it loads
// that addon, the addon links libstdc++.so.6, and Bionic has no libstdc++, so
// the load fails on every device. The server catches that failure and buffers
// log messages in memory forever instead, writing no log file at all.
//
// Covers the surface the packaged bundles call and no more. spdlog's pattern
// language is NOT parsed: the server asks for one fixed pattern or for none,
// and those two shapes are the whole vocabulary here, so setPattern() with any
// other pattern still logs, in the default shape. Writes are synchronous,
// which is what the server's own setFlushOn(0) asks for, and what keeps the
// last lines before a SIGKILL on disk.
'use strict';

const fs = require('fs');
const path = require('path');

// spdlog's level numbering, which is what the caller maps its own levels onto:
// 0 trace .. 5 critical, 6 off.
const LEVEL_NAMES = ['trace', 'debug', 'info', 'warning', 'error', 'critical'];

let globalLevel = 0;

function pad(value, width) {
	return String(value).padStart(width, '0');
}

// "%Y-%m-%d %H:%M:%S.%e" in local time, the one pattern the server asks for.
function stamp() {
	const d = new Date();
	return `${d.getFullYear()}-${pad(d.getMonth() + 1, 2)}-${pad(d.getDate(), 2)} ` +
		`${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.` +
		`${pad(d.getMilliseconds(), 3)}`;
}

class Logger {
	// loggerType and name are upstream's signature. Neither is used: both
	// logger types rotate the same way here, and the pattern the server asks
	// for has no %n, so the name never appears in a line.
	constructor(loggerType, name, filepath, maxFileSize, maxFiles) {
		this._filepath = filepath;
		this._maxFileSize = maxFileSize > 0 ? maxFileSize : Infinity;
		this._maxFiles = maxFiles > 0 ? maxFiles : 1;
		this._level = globalLevel;
		this._raw = false;
		this._reported = false;
		this._dropped = false;
		// Deliberately not caught: a logger that cannot open its file must
		// reject, so the caller's own catch prints why. Swallowing here is
		// what turns an unwritable log directory back into silence.
		fs.mkdirSync(path.dirname(filepath), { recursive: true });
		this._fd = fs.openSync(filepath, 'a');
		this._size = fs.fstatSync(this._fd).size;
	}

	// Once, then quiet. The caller's log() has no try/catch around these level
	// methods, so throwing from here would turn a full disk into a crash in
	// whatever code happened to be logging.
	_report(err) {
		if (this._reported) return;
		this._reported = true;
		console.error(`[spdlog-shim] ${this._filepath}: ${err && err.message}`);
	}

	// spdlog's rotating_file_sink naming: base.log, base.1.log .. base.N.log.
	_rotated(index) {
		if (index === 0) return this._filepath;
		const ext = path.extname(this._filepath);
		return `${this._filepath.slice(0, this._filepath.length - ext.length)}.${index}${ext}`;
	}

	_rotate() {
		try {
			fs.closeSync(this._fd);
			this._fd = null;
			// The rename into the last slot is what drops the oldest file.
			// maxFiles === 1 has no slot to rename into, so the base file is
			// truncated instead, also what spdlog does.
			for (let i = this._maxFiles - 1; i > 0; i--) {
				if (fs.existsSync(this._rotated(i - 1))) {
					fs.renameSync(this._rotated(i - 1), this._rotated(i));
				}
			}
			this._fd = fs.openSync(this._filepath, this._maxFiles > 1 ? 'a' : 'w');
			this._size = fs.fstatSync(this._fd).size;
		} catch (err) {
			this._report(err);
		}
	}

	_write(level, message) {
		if (level < this._level || this._dropped) return;
		if (this._fd === null) {
			try {
				this._fd = fs.openSync(this._filepath, 'a');
				this._size = fs.fstatSync(this._fd).size;
			} catch (err) {
				this._report(err);
				return;
			}
		}
		const line = this._raw
			? `${message}\n`
			: `${stamp()} [${LEVEL_NAMES[level]}] ${message}\n`;
		const bytes = Buffer.byteLength(line);
		if (this._size + bytes > this._maxFileSize) this._rotate();
		if (this._fd === null) return;
		try {
			fs.writeSync(this._fd, line);
			this._size += bytes;
		} catch (err) {
			this._report(err);
		}
	}

	trace(message) { this._write(0, message); }
	debug(message) { this._write(1, message); }
	info(message) { this._write(2, message); }
	warn(message) { this._write(3, message); }
	error(message) { this._write(4, message); }
	critical(message) { this._write(5, message); }

	getLevel() { return this._level; }
	setLevel(level) { this._level = level; }

	// Nothing to do: every write above is a writeSync, so there is never
	// anything buffered for flush() to push out.
	flush() {}

	setPattern() { this._raw = false; }
	clearFormatters() { this._raw = true; }

	drop() {
		this._dropped = true;
		if (this._fd === null) return;
		try { fs.closeSync(this._fd); } catch (err) { this._report(err); }
		this._fd = null;
	}
}

async function createLogger(loggerType, name, filepath, maxFileSize, maxFiles) {
	return new Logger(loggerType, name, filepath, maxFileSize, maxFiles);
}

exports.version = 'vscodroid-js-shim';
exports.Logger = Logger;
exports.setLevel = function (level) { globalLevel = level; };
// Accepted and ignored: writes are synchronous, so every message is already on
// disk whatever threshold the caller names.
exports.setFlushOn = function () {};
exports.createRotatingLogger = function (name, filepath, maxFileSize, maxFiles) {
	return createLogger('rotating', name, filepath, maxFileSize, maxFiles);
};
exports.createAsyncRotatingLogger = function (name, filepath, maxFileSize, maxFiles) {
	return createLogger('rotating_async', name, filepath, maxFileSize, maxFiles);
};
