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
    console.log('RFID status event ' + event.RFIDStatusEvent);

    if (this.oncallbacks.hasOwnProperty(RFIDScannerEvent.STATUS)) {
      this.oncallbacks[RFIDScannerEvent.STATUS].forEach((callback) => {
        callback(event.RFIDStatusEvent);
      });
    }

    if (event.RFIDStatusEvent === RFIDStatusEvent.OPENED) {
      this.opened = true;
      if (this.deferReading) {
        rfidScannerManager.read(this.config);
        this.deferReading = false;
      } else if (this.deferReading) {
          rfidScannerManager.write(this.config);
          this.deferWriting = false;
      }
    } else if (event.RFIDStatusEvent === RFIDStatusEvent.CLOSED) {
      this.opened = false;
    }
  }

  handleSettingEvent (setting) {
    console.log('Setting Event ' + JSON.stringify(setting.SettingEvent));
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

  setMode (mode, config) {
    console.log('set mode 2222', mode, rfidScannerManager)

    if (rfidScannerManager != null) {
        console.log('set mode 33333', mode, config)
        rfidScannerManager.setMode(mode, config);
    }
  }

  read (config = {}) {
    this.config = config;

    if (this.opened) {
      rfidScannerManager.read(this.config);
    } else {
      this.deferReading = true;
    }
  }

  write (config) {
    this.config = config;

    if (this.opened) {
      rfidScannerManager.write(config);
    } else {
      this.deferWriting = true;
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

  settingAntennas (powerLevel) {
    rfidScannerManager.settingAntennas(powerLevel);
  }
  
  gettingAntennas () {
    rfidScannerManager.gettingAntennas();
  }

  settingBeeper (beeperVolume) {
    rfidScannerManager.settingBeeper(beeperVolume);
  }

  gettingBeeper () {
    rfidScannerManager.gettingBeeper();
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
      this.oncallbacks[event].forEach((funct, index) => {
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
