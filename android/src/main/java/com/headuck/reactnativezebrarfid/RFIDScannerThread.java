package com.headuck.reactnativezebrarfid;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.lang.NullPointerException;

import com.zebra.rfid.api3.*;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

public abstract class RFIDScannerThread extends Thread implements RfidEventsListener {

    private final static String READ = "read";
    private final static String WRITE = "write";
    private final static String LOCK = "lock";

    private ReactApplicationContext context;

    private Readers readers = null;
    private ArrayList<ReaderDevice> deviceList = null;
    private ReaderDevice rfidReaderDevice = null;
    boolean tempDisconnected = false;
    private Boolean reading = false;
    private Boolean writing = false;
    private ReadableMap config = null;
    private String rfidMode = READ;
    private Boolean deferTriggerReleased = false;

    public RFIDScannerThread(ReactApplicationContext context) {
        this.context = context;
    }

    public void run() {

    }

    private void connect() {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (rfidReaderDevice.getRFIDReader().isConnected()) return;
            disconnect();
        }
        try {

            Log.v("RFID", "initScanner");

            ArrayList<ReaderDevice> availableRFIDReaderList = null;
            try {
                readers = new Readers(this.context, ENUM_TRANSPORT.SERVICE_SERIAL);
                availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
                Log.v("RFID", "Available number of reader : " + availableRFIDReaderList.size());
                deviceList = availableRFIDReaderList;

            } catch (InvalidUsageException e) {
                Log.e("RFID", "Init scanner error - invalid message: " + e.getMessage());
            } catch (NullPointerException ex) {
                Log.e("RFID", "Blue tooth not support on device");
            }

            int listSize = (availableRFIDReaderList == null) ? 0 : availableRFIDReaderList.size();
            Log.v("RFID", "Available number of reader : " + listSize);

            if (listSize > 0) {
                ReaderDevice readerDevice = availableRFIDReaderList.get(0);
                RFIDReader rfidReader = readerDevice.getRFIDReader();
                // Connect to RFID reader

                if (rfidReader != null) {
                    while (true) {
                        try {
                            rfidReader.connect();
                            rfidReader.Config.getDeviceStatus(true, false, false);
                            rfidReader.Events.addEventsListener(this);
                            // Subscribe required status notification
                            rfidReader.Events.setInventoryStartEvent(true);
                            rfidReader.Events.setInventoryStopEvent(true);
                            // enables tag read notification
                            rfidReader.Events.setTagReadEvent(true);
                            rfidReader.Events.setReaderDisconnectEvent(true);
                            rfidReader.Events.setBatteryEvent(true);
                            rfidReader.Events.setBatchModeEvent(true);
                            rfidReader.Events.setHandheldEvent(true);
                            // Set trigger mode
                            setTriggerImmediate(rfidReader);
                            break;
                        } catch (OperationFailureException ex) {
                            if (ex.getResults() == RFIDResults.RFID_READER_REGION_NOT_CONFIGURED) {
                                // Get and Set regulatory configuration settings
                                try {
                                    RegulatoryConfig regulatoryConfig = rfidReader.Config.getRegulatoryConfig();
                                    SupportedRegions regions = rfidReader.ReaderCapabilities.SupportedRegions;
                                    int len = regions.length();
                                    boolean regionSet = false;
                                    for (int i = 0; i < len; i++) {
                                        RegionInfo regionInfo = regions.getRegionInfo(i);
                                        if ("HKG".equals(regionInfo.getRegionCode())) {
                                            regulatoryConfig.setRegion(regionInfo.getRegionCode());
                                            rfidReader.Config.setRegulatoryConfig(regulatoryConfig);
                                            Log.i("RFID", "Region set to " + regionInfo.getName());
                                            regionSet = true;
                                            break;
                                        }
                                    }
                                    if (!regionSet) {
                                        err = "Region not found";
                                        break;
                                    }
                                } catch (OperationFailureException ex1) {
                                    err = "Error setting RFID region: " + ex1.getMessage();
                                    break;
                                }
                            } else if (ex.getResults() == RFIDResults.RFID_CONNECTION_PASSWORD_ERROR) {
                                // Password error
                                err = "Password error";
                                break;
                            } else if (ex.getResults() == RFIDResults.RFID_BATCHMODE_IN_PROGRESS) {
                                // handle batch mode related stuff
                                err = "Batch mode in progress";
                                break;
                            } else {
                                err = ex.getResults().toString();
                                break;
                            }
                        } catch (InvalidUsageException e1) {
                            Log.e("RFID", "InvalidUsageException: " + e1.getMessage() + " " + e1.getInfo());
                            err = "Invalid usage " + e1.getMessage();
                            break;
                        }
                    }
                } else {
                    err = "Cannot get rfid reader";
                }
                if (err == null) {
                    // Connect success
                    rfidReaderDevice = readerDevice;
                    tempDisconnected = false;
                    WritableMap event = Arguments.createMap();
                    event.putString("RFIDStatusEvent", "opened");
                    this.dispatchEvent("RFIDStatusEvent", event);
                    Log.i("RFID", "Connected to " + rfidReaderDevice.getName());
                    return;
                }
            } else {
                err = "No connected device";
            }
        } catch (InvalidUsageException e) {
            err = "connect: invalid usage error: " + e.getMessage();
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    /**
     * Set trigger mode
     */
    private void setTriggerImmediate(RFIDReader reader) throws InvalidUsageException, OperationFailureException {
        TriggerInfo triggerInfo = new TriggerInfo();
        // Start trigger: set to immediate mode
        triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        // Stop trigger: set to immediate mode
        triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
        reader.Config.setStartTrigger(triggerInfo.StartTrigger);
        reader.Config.setStopTrigger(triggerInfo.StopTrigger);
    }

    private void disconnect() {

        if (this.rfidReaderDevice != null){
            RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
            String err = null;
            if (!rfidReader.isConnected()) {
                Log.i("RFID", "disconnect: already disconnected");
                // already disconnected
            } else {
                try {
                    rfidReader.disconnect();
                } catch (InvalidUsageException e) {
                    err = "disconnect: invalid usage error: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "disconnect: " + ex.getResults().toString();
                }
            }
            try {
                if (rfidReader.Events != null) {
                    rfidReader.Events.removeEventsListener(this);
                }
            } catch (InvalidUsageException e) {
                err = "disconnect: invalid usage error when removing events: " + e.getMessage();
            } catch (OperationFailureException ex) {
                err = "disconnect: error removing events: " + ex.getResults().toString();
            }
            if (err != null) {
                Log.e("RFID", err);
            }
            // Ignore error and send feedback
            WritableMap event = Arguments.createMap();
            event.putString("RFIDStatusEvent", "closed");
            this.dispatchEvent("RFIDStatusEvent", event);
            rfidReaderDevice = null;
            tempDisconnected = false;
        } else {
            Log.w("RFID", "disconnect: no device was connected");
        }

    }

    public abstract void dispatchEvent(String name, WritableMap data);
    public abstract void dispatchEvent(String name, String data);
    public abstract void dispatchEvent(String name, WritableArray data);

    public void onHostResume() {
        if (readers != null){
            this.connect();
        } else {
             Log.e("RFID", "Can't resume - reader is null");
        }
    }

    public void onHostPause() {
        if (this.reading){
            this.cancel();
        }
        this.disconnect();
    }

    public void onHostDestroy() {
        if (this.reading){
            this.cancel();
        }
        shutdown();
    }

    public void onCatalystInstanceDestroy() {
        if (this.reading){
            this.cancel();
        }
        shutdown();
    }

    public void init(Context context) {
        // Register receiver
        Log.v("RFID", "init");
        readers = new Readers(context, ENUM_TRANSPORT.BLUETOOTH);
        try {
            ArrayList<ReaderDevice> availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
            Log.v("RFID", "Available number of reader : " + availableRFIDReaderList.size());
            deviceList = availableRFIDReaderList;

            Log.v("RFID", "Scanner thread initialized");
        } catch (InvalidUsageException e) {
            Log.e("RFID", "Init scanner error - invalid message: " + e.getMessage());
        } catch (NullPointerException ex) {
            Log.e("RFID", "Blue tooth not support on device");
        }
        tempDisconnected = false;
        reading = false;
        this.connect();
    }

    public void shutdown() {
        if (this.rfidReaderDevice != null) {
            disconnect();
        }
        // Unregister receiver
        if (readers != null) {
            readers.Dispose();
            readers = null;
        }
        deviceList = null;
    }

    public void setMode(String mode, ReadableMap config) {
        this.rfidMode = mode;
        this.config = config;
    }

    public void read(ReadableMap config) {
        if (this.reading) {
            Log.e("RFID", "already reading");
            return;
        }
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    // Perform inventory
                    rfidReader.Actions.Inventory.perform(null, null, null);
                    reading = true;
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }


    // configuration
    private void setAntennaPower(int power) {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    // set antenna configurations
                    Antennas.AntennaRfConfig config = rfidReader.Config.Antennas.getAntennaRfConfig(1);
                    config.setTransmitPowerIndex(power);
                    config.setrfModeTableIndex(0);
                    config.setTari(0);
                    rfidReader.Config.Antennas.setAntennaRfConfig(1, config);
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    private void setSingulation(SESSION session, INVENTORY_STATE state) {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    // Set the singulation control
                    Antennas.SingulationControl s1_singulationControl = rfidReader.Config.Antennas.getSingulationControl(1);
                    s1_singulationControl.setSession(session);
                    s1_singulationControl.Action.setInventoryState(state);
                    s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
                    rfidReader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    private void setDPO(boolean bEnable) {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    rfidReader.Config.setDPOState(bEnable ? DYNAMIC_POWER_OPTIMIZATION.ENABLE : DYNAMIC_POWER_OPTIMIZATION.DISABLE);
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    private void setAccessOperationConfiguration() {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    // set required power and profile
                    setAntennaPower(240);
                    // in case of RFD8500 disable DPO
                    if (rfidReader.getHostName().contains("RFD8500")) {
                        setDPO(false);
                    }
                    // set access operation time out value to 1 second, so reader will tries for a second
                    // to perform operation before timing out
                    rfidReader.Config.setAccessOperationWaitTimeout(1000);
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    //
    // method to write data
    //
    private void writeTag(String sourceEPC, String Password, MEMORY_BANK memory_bank, String targetData, int offset) {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    writing = true;
                    WritableMap startEvent = Arguments.createMap();
                    startEvent.putString("RFIDStatusEvent", "writeStart");
                    this.dispatchEvent("RFIDStatusEvent", startEvent);
                    TagData tagData = null;
                    String tagId = sourceEPC;
                    TagAccess tagAccess = new TagAccess();
                    TagAccess.WriteAccessParams writeAccessParams = tagAccess.new WriteAccessParams();
                    String writeData = targetData; //write data in string
                    writeAccessParams.setAccessPassword(Long.parseLong(Password,16));
                    writeAccessParams.setMemoryBank(memory_bank);
                    writeAccessParams.setOffset(offset); // start writing from word offset 0
                    writeAccessParams.setWriteData(writeData);
                    // set retries in case of partial write happens
                    writeAccessParams.setWriteRetries(3);
                    // data length in words
                    writeAccessParams.setWriteDataLength(writeData.length() / 4);
                    // 5th parameter bPrefilter flag is true which means API will apply pre filter internally
                    // 6th parameter should be true in case of changing EPC ID it self i.e. source and target both is EPC
                    boolean useTIDfilter = memory_bank == MEMORY_BANK.MEMORY_BANK_EPC;
                    rfidReader.Actions.TagAccess.writeWait(tagId, writeAccessParams, null, tagData, true, useTIDfilter);
                } catch (InvalidUsageException e) {
                    err = "read: invalid usage error on scanner read: " + e.getMessage();
                } catch (OperationFailureException ex) {
                    err = "read: error setting up scanner read: " + ex.getResults().toString();
                }
                writing = false;
                WritableMap stopEvent = Arguments.createMap();
                stopEvent.putString("RFIDStatusEvent", "writeStop");
                this.dispatchEvent("RFIDStatusEvent", stopEvent);

                WritableMap errEvent = Arguments.createMap();
                errEvent.putString("RFIDStatusEvent", err);
                this.dispatchEvent("RFIDStatusEvent", errEvent);
                // if (deferTriggerReleased) {
                //     deferTriggerReleased = false;
                //     this.dispatchEvent("RFIDStatusEvent", Arguments.createMap());
                // }
            }
        } else {
            err = "read: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }


    public void write(ReadableMap config) {
        // one time setup to suite the access operation, if reader is already in that state it can be avoided
        setAccessOperationConfiguration();
        // all are hex strings
        // String EPC = "3005FB63AC1F3681EC880468";
        // String data = "437573746F6D204461746120696E2055736572206D656D6F72792062616E6B0D";
        String password = "0";//"5A454252";
        // perform write
        MEMORY_BANK user = MEMORY_BANK.MEMORY_BANK_USER;
        MEMORY_BANK epc = MEMORY_BANK.MEMORY_BANK_EPC;

        writeTag(config.getString("epc"), password, epc, config.getString("data"), 0);
    }


    // public void write (String epc, String data) {
    //     if (this.writing) {
    //         Log.e("RFID", "already writing");
    //         return;
    //     }
    //     String err = null;
    //     if (this.rfidReaderDevice != null) {
    //         if (!rfidReaderDevice.getRFIDReader().isConnected()) {
    //             err = "read: device not connected";
    //         } else {
    //             RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
    //             try {
    //                 // one time setup to suite the access operation, if reader is already in that state it can be avoided
    //                 setAccessOperationConfiguration();
    //                 // all are hex strings
    //                 writing = true;
    //                 String password = "0";
    //                 // perform write
    //                 writeTag(epc, password, MEMORY_BANK.MEMORY_BANK_USER, data, 0);
    //             } catch (InvalidUsageException e) {
    //                 err = "read: invalid usage error on scanner read: " + e.getMessage();
    //             } catch (OperationFailureException ex) {
    //                 err = "read: error setting up scanner read: " + ex.getResults().toString();
    //             }
    //         }
    //     } else {
    //         err = "read: device not initialised";
    //     }
    //     if (err != null) {
    //         Log.e("RFID", err);
    //     }
    // }


    public void cancel() {
        String err = null;
        if (rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "cancel: device not connected";
            } else {
                if (reading) {
                    RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                    try {
                        switch (rfidMode) {
                            case READ:
                                // Stop inventory
                                rfidReader.Actions.Inventory.stop();
                                break;
                            case WRITE:
                                // Will stop by itself
                                break;
                        }
                    } catch (InvalidUsageException e) {
                        err = "cancel: invalid usage error on scanner read: " + e.getMessage();
                    } catch (OperationFailureException ex) {
                        err = "cancel: error setting up scanner read: " + ex.getResults().toString();
                    }
                    reading = false;
                }
            }
        } else {
            err = "cancel: device not initialised";
        }
        if (err != null) {
            Log.e("RFID", err);
        }
    }

    public void reconnect() {
        if (rfidReaderDevice != null) {
            if (tempDisconnected) {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                if (!rfidReader.isConnected()) {
                    String err = null;
                    try {
                        // Stop inventory
                        rfidReader.reconnect();
                    } catch (InvalidUsageException e) {
                        err = "reconnect: invalid usage error: " + e.getMessage();
                    } catch (OperationFailureException ex) {
                        err = "reconnect error: " + ex.getResults().toString();
                    }
                    if (err != null) {
                        Log.e("RFID", err);
                    } else {
                        tempDisconnected = false;
                        WritableMap event = Arguments.createMap();
                        event.putString("RFIDStatusEvent", "opened");
                        this.dispatchEvent("RFIDStatusEvent", event);
                        Log.i("RFID", "Reconnected to " + rfidReaderDevice.getName());
                    }
                } else {
                    Log.i("RFID", rfidReaderDevice.getName() + " is already connected");
                }
            } else {
                Log.i("RFID", "reconnect: not temp disconnected");
            }
        } else {
            Log.i("RFID", "reconnect: device is null");
        }
    }

    public void settingAntennas(int powerLevel) {
        String err = null;
        try {
            if (this.rfidReaderDevice != null) {
                Antennas.AntennaRfConfig antennaRfConfig = this.rfidReaderDevice.getRFIDReader().Config.Antennas.getAntennaRfConfig(1);
                Log.i("RFID","ori config: " +antennaRfConfig);
                if(powerLevel != antennaRfConfig.getTransmitPowerIndex()) {
                    antennaRfConfig.setrfModeTableIndex(4);
                    antennaRfConfig.setTari(0);
                    antennaRfConfig.setTransmitPowerIndex(powerLevel);
                    this.rfidReaderDevice.getRFIDReader().Config.Antennas.setAntennaRfConfig(1, antennaRfConfig);
                    this.rfidReaderDevice.getRFIDReader().Config.saveConfig();
                }
                WritableMap event = Arguments.createMap();
                event.putString("SettingEvent", "Setting Antennas Completed");
                Log.i("RFID","" + event);
                this.dispatchEvent("SettingEvent", event);
                Log.i("RFID", "Setting antennas completed");
            }
        } catch (InvalidUsageException e) {
            err = "Setting Invalid error " + e.getMessage();
        } catch (OperationFailureException e) {
            err = "Setting Operation error " + e.getMessage();
        }
        if (err != null) {
            Log.e("RFID", err);
            WritableMap event = Arguments.createMap();
            event.putString("SettingEvent", "Setting Antennas Failed");
            this.dispatchEvent("SettingEvent", event);
        }
    }
    public void gettingAntennas() {
        Log.i("TEST123", "WTFFFF");
        String err = null;
        try {
            if (this.rfidReaderDevice != null) {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();

                Antennas.AntennaRfConfig antennaRfConfig = rfidReader.Config.Antennas.getAntennaRfConfig(1);


                WritableMap data = Arguments.createMap();
                data.putString("ModeTableIndex", "" + antennaRfConfig.getrfModeTableIndex());
                data.putString("Tari", "" + antennaRfConfig.getTari());
                data.putString("PowerIndex", "" + antennaRfConfig.getTransmitPowerIndex());

                WritableMap event = Arguments.createMap();
                event.putMap("SettingEvent", data);
                Log.i("RFID", "successful");
                this.dispatchEvent("SettingEvent", event);
            }
            Log.i("RFID", "successful22");
        } catch (InvalidUsageException e) {
            err = "Setting Invalid error " + e.getMessage();
        } catch (OperationFailureException e) {
            err = "Setting Operation error " + e.getMessage();
        }
        if (err != null) {
            Log.e("RFID", err);
            WritableMap event = Arguments.createMap();
            event.putString("SettingEvent", "Getting Beeper Volume Failed");
            this.dispatchEvent("SettingEvent", event);
        }
    }
    public void gettingBeeper() {
        String err = null;
        try {
            if (this.rfidReaderDevice != null) {
                BEEPER_VOLUME oribeeperVolume = this.rfidReaderDevice.getRFIDReader().Config.getBeeperVolume();
                WritableMap event = Arguments.createMap();
                event.putString("SettingEvent", oribeeperVolume.toString());
                Log.i("RFID", "successful");
                Log.i("RFID","ORIG Volume " + oribeeperVolume.toString());
                this.dispatchEvent("SettingEvent", event);
            }
            Log.i("RFID", "successful22");
        } catch (InvalidUsageException e) {
            err = "Setting Invalid error " + e.getMessage();
        } catch (OperationFailureException e) {
            err = "Setting Operation error " + e.getMessage();
        }
        if (err != null) {
            Log.e("RFID", err);
            WritableMap event = Arguments.createMap();
            event.putString("SettingEvent", "Getting Beeper Volume Failed");
            this.dispatchEvent("SettingEvent", event);
        }
    }
    public void settingBeeper(String beeperVolume) {
        String err = null;
        try {
            if (rfidReaderDevice != null && beeperVolume != null) {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                BEEPER_VOLUME oribeeperVolume = rfidReader.Config.getBeeperVolume();
                if (beeperVolume != oribeeperVolume.toString()) {
                    switch (beeperVolume) {
                        case "HIGH":
                            this.rfidReaderDevice.getRFIDReader().Config.setBeeperVolume(BEEPER_VOLUME.HIGH_BEEP);
                            break;
                        case "MEDIUM":
                            this.rfidReaderDevice.getRFIDReader().Config.setBeeperVolume(BEEPER_VOLUME.MEDIUM_BEEP);
                            break;
                        case "LOW":
                            this.rfidReaderDevice.getRFIDReader().Config.setBeeperVolume(BEEPER_VOLUME.LOW_BEEP);
                            break;
                        case "QUIET":
                            this.rfidReaderDevice.getRFIDReader().Config.setBeeperVolume(BEEPER_VOLUME.QUIET_BEEP);
                            break;
                    }
                    this.rfidReaderDevice.getRFIDReader().Config.saveConfig();
                }
                WritableMap event = Arguments.createMap();
                event.putString("SettingEvent", "Setting Bepper Completed");
                Log.i("RFID","" + event);
                this.dispatchEvent("SettingEvent", event);
                Log.i("RFID", "Setting bepper completed");
            }
        } catch (InvalidUsageException e) {
            err = "Setting Invalid error " + e.getMessage();
        } catch (OperationFailureException e) {
            err = "Setting Operation error " + e.getMessage();
        }
        if (err != null) {
            Log.e("RFID", err);
            WritableMap event = Arguments.createMap();
            event.putString("SettingEvent", "Setting Beeper Failed");
            this.dispatchEvent("SettingEvent", event);
        }
    }

    @Override
    public void eventReadNotify(RfidReadEvents rfidReadEvents) {
        // reader not active
        if (rfidReaderDevice == null) return;
        RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();

        TagDataArray tagArray = rfidReader.Actions.getReadTagsEx(1000);
        if (tagArray != null) {
            WritableArray rfidTags = Arguments.createArray();
            for (int i = 0; i < tagArray.getLength(); i++) {
                TagData tag = tagArray.getTags()[i];

                Log.i("RFID", "Tag ID = " + tag.getTagID());
                if (tag.getOpCode() == null) {
                    Log.w ("RFID", "null opcode");
                } else {
                    Log.w ("RFID", "opcode " + tag.getOpCode().toString());
                }
                this.dispatchEvent("TagEvent", tag.getTagID());
                rfidTags.pushString(tag.getTagID());
            }
            this.dispatchEvent("TagsEvent", rfidTags);
        }
    }

    @Override
    public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
        WritableMap event = Arguments.createMap();

        STATUS_EVENT_TYPE statusEventType = rfidStatusEvents.StatusEventData.getStatusEventType();
        if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStart");
            reading = true;
        } else if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStop");
            reading = false;
        } else if (statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            event.putString("RFIDStatusEvent", "disconnect");
            reading = false;
            tempDisconnected = true;
        } else if (statusEventType == STATUS_EVENT_TYPE.BATCH_MODE_EVENT) {
            event.putString("RFIDStatusEvent", "batchMode");
            Log.i("RFID", "batch mode event: " + rfidStatusEvents.StatusEventData.BatchModeEventData.toString());
        } else if (statusEventType == STATUS_EVENT_TYPE.BATTERY_EVENT) {
            int level = rfidStatusEvents.StatusEventData.BatteryData.getLevel();
            event.putString("RFIDStatusEvent", "battery " + level);
            Log.i("RFID", "battery level " + level);
        } else if (statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
            HANDHELD_TRIGGER_EVENT_TYPE eventData = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
            if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                switch (rfidMode) {
                    case READ:
                        this.read(this.config);
                        break;
                    case WRITE:
                        if (!writing) {
                            this.write(this.config);
                        }
                        break;
                }
            } else if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                this.cancel();
            }
        }
        if (event.hasKey("RFIDStatusEvent")) {
            if (!writing) {
                this.dispatchEvent("RFIDStatusEvent", event);
            } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                deferTriggerReleased = true;
            }
        }
    }
}