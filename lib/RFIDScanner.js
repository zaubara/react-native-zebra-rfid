import { NativeModules, DeviceEventEmitter } from 'react-native';
import { RFIDScannerEvent } from './RFIDScannerEvent';
import { RFIDStatusEvent } from './RFIDStatusEvent';

const rfidScannerManager = NativeModules.RFIDScannerManager;

let instance = null;

export class RFIDScanner {
  constructor () {
    if (!instance) {
      instance = this;
      this.opened = false;
      this.deferReading = false;
      this.deferWriting = false;
      this.oncallbacks = [];
      this.config = {};

      DeviceEventEmitter.addListener('TagEvent', this.handleTagEvent.bind(this));
      DeviceEventEmitter.addListener('TagsEvent', this.handleTagsEvent.bind(this));
      DeviceEventEmitter.addListener('RFIDStatusEvent', this.handleStatusEvent.bind(this));
      DeviceEventEmitter.addListener('SettingEvent', this.handleSettingEvent.bind(this));
    }
  }

  handleStatusEvent (event) {
    // console.log('RFIDStatusEvent', event.RFIDStatusEvent);
    if (this.oncallbacks.hasOwnProperty(RFIDScannerEvent.STATUS)) {
      this.oncallbacks[RFIDScannerEvent.STATUS].forEach((callback) => {
        callback(event.RFIDStatusEvent);
      });
    }
  }

  handleSettingEvent (setting) {
    // console.log('SettingEvent', JSON.stringify(setting.SettingEvent));
    if (this.oncallbacks.hasOwnProperty(RFIDScannerEvent.SETTING)) {
      this.oncallbacks[RFIDScannerEvent.SETTING].forEach((callback) => {
        callback(setting);
      });
    }
  }

  handleTagEvent (tag) {
    if (this.oncallbacks.hasOwnProperty(RFIDScannerEvent.TAG)) {
      this.oncallbacks[RFIDScannerEvent.TAG].forEach((callback) => {
        callback(tag);
      });
    }
  }

  handleTagsEvent (tags) {
    if (this.oncallbacks.hasOwnProperty(RFIDScannerEvent.TAGS)) {
      this.oncallbacks[RFIDScannerEvent.TAGS].forEach((callback) => {
        callback(tags);
      });
    }
  }

  init () {
    rfidScannerManager.init();
  }

  setMode (mode, config = {}) {
    if (rfidScannerManager != null) {
        rfidScannerManager.setMode(mode, config);
    }
  }

  reconnect () {
    rfidScannerManager.reconnect();
  }

  cancel () {
    rfidScannerManager.cancel();
  }

  shutdown () {
    rfidScannerManager.shutdown();
  }

  on (event, callback) {
    if (!this.oncallbacks[event]) { this.oncallbacks[event] = [] }
    this.oncallbacks[event].push(callback);
  }

  removeon (event, callback) {
    if (this.oncallbacks.hasOwnProperty(event)) {
      this.oncallbacks[event].forEach((funct, index) => {
        if (funct.toString() === callback.toString()) {
          this.oncallbacks[event].splice(index, 1);
        }
      });
    }
  }

  removeonevent (event) {
    if (this.oncallbacks.hasOwnProperty(event)) {
      this.oncallbacks[event].forEach(() => {
          this.oncallbacks[event] = []
      });
    }
  }

  hason (event, callback) {
    let result = false;
    if (this.oncallbacks.hasOwnProperty(event)) {
      this.oncallbacks[event].forEach((funct, index) => {
        if (funct.toString() === callback.toString()) {
          result = true;
        }
      });
    }
    return result;
  }
}

export default new RFIDScanner();
