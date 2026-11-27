'use strict';
// Test-only: installs as a real extension, then deliberately fails activation.
// This fixture must never be bundled in the production APK or remote agent.
exports.activate = () => { throw new Error('Unsupported platform: android (compatibility proof fixture)'); };
