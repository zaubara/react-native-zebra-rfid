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

    private final static String NONE = "none";
    private final static String READ = "read";
    private final static String WRITE = "write";
    private final static String LOCK = "lock";

    private final static MEMORY_BANK USER = MEMORY_BANK.MEMORY_BANK_USER;
    private final static MEMORY_BANK EPC = MEMORY_BANK.MEMORY_BANK_EPC;

    private ReactApplicationContext context;

    private Readers readers = null;
    private ArrayList<ReaderDevice> deviceList = null;
    private ReaderDevice rfidReaderDevice = null;
    boolean tempDisconnected = false;

    private String rfidMode = NONE;
    private Boolean active = false;
    private ReadableMap config = null;
    private Boolean deferTriggerReleased = false;

    public RFIDScannerThread(ReactApplicationContext context) {
        this.context = context;
    }

    public void run() {}

    public abstract void dispatchEvent(String name, WritableMap data);
    public abstract void dispatchEvent(String name, String data);
    public abstract void dispatchEvent(String name, WritableArray data);

    // -------
    // Helpers
    // -------
    private RFIDReader getConnectedRFIDReader() throws Exception {
        if (rfidReaderDevice != null) {
            if (rfidReaderDevice.getRFIDReader().isConnected()) {
                return rfidReaderDevice.getRFIDReader();
            } else {
                log("cancel: device not connected");
                throw new Exception("cancel: device not connected");
            }
        } else {
            log("cancel: device not initialised");
            throw new Exception("cancel: device not initialised");
        }
    }

    private void LogEvent(String message) {
        WritableMap event = Arguments.createMap();
        event.putString("RFIDStatusEvent", "LOG: " + message);
        this.dispatchEvent("RFIDStatusEvent", event);
    }
    private void log(String message) {
        Log.i("TEST123", message);
    }

    // ----------
    // Life Cycle
    // ----------

    public void init(Context context) {
        // Register receiver
        readers = new Readers(context, ENUM_TRANSPORT.BLUETOOTH);
        try {
            ArrayList<ReaderDevice> availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
            deviceList = availableRFIDReaderList;
            log("RFID - Scanner thread initialized");
        } catch (InvalidUsageException e) {
            log("RFID - Init scanner error - invalid message: " + e.getMessage());
        } catch (NullPointerException ex) {
            log("RFID - Blue tooth not support on device");
        }

        tempDisconnected = false;
        active = false;
        rfidMode = NONE;
        this.connect();
    }

    private void connect() {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (rfidReaderDevice.getRFIDReader().isConnected()) return;
            disconnect();
        }
        try {
            ArrayList<ReaderDevice> availableRFIDReaderList = null;
            try {
                readers = new Readers(this.context, ENUM_TRANSPORT.SERVICE_SERIAL);
                availableRFIDReaderList = readers.GetAvailableRFIDReaderList();
                deviceList = availableRFIDReaderList;
            } catch (InvalidUsageException e) {
                log("connect RFID - Init scanner error - invalid message: " + e.getMessage());
            } catch (NullPointerException ex) {
                log("RFID - Blue tooth not support on device");
            }

            int listSize = (availableRFIDReaderList == null) ? 0 : availableRFIDReaderList.size();
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
                            setTriggerMode(rfidReader);
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
                        } catch (Exception exc) {
                            err = exc.getMessage();
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
            log("Connect RFID - " + err);
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
                log("RFID - " + err);
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
    // ----------
    // App Events
    // ----------

    public void onHostResume() {
        if (readers != null){
            this.connect();
        } else {
             Log.e("RFID", "Can't resume - reader is null");
        }
    }

    public void onHostPause() {
        this.cancel();
        this.disconnect();
    }

    public void onHostDestroy() {
        this.cancel();
        shutdown();
    }

    public void onCatalystInstanceDestroy() {
        this.cancel();
        shutdown();
    }

    // ----------
    // Operations
    // ----------

    public void setMode(String mode, ReadableMap config) {
        LogEvent("SET MODE: " + mode);
        if (active) {
            cancel();
        }
        this.rfidMode = mode;
        this.config = config;
        log("MODE: " + mode);
    }

    public void cancel() {
        log("CANCEL 111");
        String err = null;
        try {
            switch (rfidMode) {
                case READ:
                    // Stop inventory
                    stopInventory();
                    break;
                case WRITE:
                    // Will stop by itself
                    break;
            }
        } catch (InvalidUsageException e) {
            err = "cancel: invalid usage error on scanner read: " + e.getMessage();
        } catch (OperationFailureException ex) {
            err = "cancel: error setting up scanner read: " + ex.getResults().toString();
        } catch(Exception exc) {
            err = exc.getMessage();
        }

        if (err != null) {
            log("RFID - " + err);
        }
        log("CANCEL 222");
    }

    public void startInventory(ReadableMap config) throws Exception {
        log("startInventory 111: " + !active);
        if (!active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.Inventory.perform(null, null, null);
            active = true;
            log("startInventory 222");
        }
    }

    public void stopInventory () throws Exception {
        log("stopInventory 111: " + active);
        if (active) {
            RFIDReader rfidReader = getConnectedRFIDReader();
            rfidReader.Actions.Inventory.stop();
            active = false;
            log("stopInventory 222");
        }
    }

    private void writeTag(String sourceEPC, String Password, MEMORY_BANK memory_bank, String targetData, int offset) {
        String err = null;
        if (this.rfidReaderDevice != null) {
            if (!rfidReaderDevice.getRFIDReader().isConnected()) {
                err = "read: device not connected";
            } else {
                RFIDReader rfidReader = rfidReaderDevice.getRFIDReader();
                try {
                    active = true;
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
                active = false;
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
            log("RFID - " + err);
        }
    }


    public void write(ReadableMap config) throws Exception {
        RFIDReader rfidReader = getConnectedRFIDReader();
        // one time setup to suite the access operation, if reader is already in that state it can be avoided
        setWriteConfig(rfidReader);
        // all are hex strings
        // String EPC = "3005FB63AC1F3681EC880468";
        // String data = "437573746F6D204461746120696E2055736572206D656D6F72792062616E6B0D";
        String password = "0";//"5A454252";
        // perform write
        writeTag(config.getString("user"), password, USER, config.getString("data"), 0);
    }

    // -------------
    // Configuration
    // -------------
    private void setTriggerMode(RFIDReader reader) throws Exception {
        TriggerInfo triggerInfo = new TriggerInfo();
        // Start trigger: set to immediate mode
        triggerInfo.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        // Stop trigger: set to immediate mode
        triggerInfo.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE);
        reader.Config.setStartTrigger(triggerInfo.StartTrigger);
        reader.Config.setStopTrigger(triggerInfo.StopTrigger);
    }

    private void setAntennaPower(RFIDReader reader, int power) throws InvalidUsageException, OperationFailureException {
        Antennas.AntennaRfConfig config = reader.Config.Antennas.getAntennaRfConfig(1);
        config.setTransmitPowerIndex(power);
        config.setrfModeTableIndex(0); // antennaRfConfig.setrfModeTableIndex(4);
        config.setTari(0);
        reader.Config.Antennas.setAntennaRfConfig(1, config);
        reader.Config.saveConfig();

        WritableMap event = Arguments.createMap();
        event.putString("SettingsEvent", "Setting Antennas to " + power + " completed");
        this.dispatchEvent("SettingEvent", event);
    }

    private void setSingulation(RFIDReader reader, SESSION session, INVENTORY_STATE state) throws InvalidUsageException, OperationFailureException {
        Antennas.SingulationControl s1_singulationControl = reader.Config.Antennas.getSingulationControl(1);
        s1_singulationControl.setSession(session);
        s1_singulationControl.Action.setInventoryState(state);
        s1_singulationControl.Action.setSLFlag(SL_FLAG.SL_ALL);
        reader.Config.Antennas.setSingulationControl(1, s1_singulationControl);
    }

    private void setDPO(RFIDReader reader, boolean bEnable) throws InvalidUsageException, OperationFailureException {
       reader.Config.setDPOState(bEnable ? DYNAMIC_POWER_OPTIMIZATION.ENABLE : DYNAMIC_POWER_OPTIMIZATION.DISABLE);
    }

    private void setWriteConfig(RFIDReader reader) throws InvalidUsageException, OperationFailureException {
        // set required power and profile
        setAntennaPower(reader, 240);
        // in case of RFD8500 disable DPO
        if (reader.getHostName().contains("RFD8500")) {
            setDPO(reader,false);
        }
        // set access operation time out value to 1 second, so reader will tries for a second
        // to perform operation before timing out
        reader.Config.setAccessOperationWaitTimeout(1000);
    }

    // -------------------
    // RfidEventsListeners
    // -------------------
    @Override
    public void eventReadNotify(RfidReadEvents rfidReadEvents) {
        log("eventReadNotify 111");
        String err = null;
        try {
            RFIDReader rfidReader = getConnectedRFIDReader();
            TagDataArray tagArray = rfidReader.Actions.getReadTagsEx(100);
            if (tagArray != null) {
                WritableArray rfidTags = Arguments.createArray();
                for (int i = 0; i < tagArray.getLength(); i++) {
                    TagData tag = tagArray.getTags()[i];
                    this.dispatchEvent("TagEvent", tag.getTagID());
                    rfidTags.pushString(tag.getTagID());
                }
                log("eventReadNotify 111-TAGS-111");
                this.dispatchEvent("TagsEvent", rfidTags);
                log("eventReadNotify 111-TAGS-222");
            }
        } catch (Exception e) {
            err = e.getMessage();
        }

        if (err != null) {
            log("RFID - " + err);
        }
        log("eventReadNotify 222");
    }

    @Override
    public void eventStatusNotify(RfidStatusEvents rfidStatusEvents) {
        WritableMap event = Arguments.createMap();
        STATUS_EVENT_TYPE statusEventType = rfidStatusEvents.StatusEventData.getStatusEventType();

        if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_START_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStart");
        } else if (statusEventType == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            event.putString("RFIDStatusEvent", "inventoryStop");
        } else if (statusEventType == STATUS_EVENT_TYPE.DISCONNECTION_EVENT) {
            event.putString("RFIDStatusEvent", "disconnect");
            tempDisconnected = true;
        } else if (statusEventType == STATUS_EVENT_TYPE.BATCH_MODE_EVENT) {
            event.putString("RFIDStatusEvent", "batchMode" + rfidStatusEvents.StatusEventData.BatchModeEventData.toString());
        } else if (statusEventType == STATUS_EVENT_TYPE.BATTERY_EVENT) {
            event.putString("RFIDStatusEvent", "battery " + rfidStatusEvents.StatusEventData.BatteryData.getLevel());
        } else if (statusEventType == STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT) {
            HANDHELD_TRIGGER_EVENT_TYPE eventData = rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent();
            String err = null;

            if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED) {
                switch (rfidMode) {
                    case READ:
                        try {
                            this.startInventory(this.config);
                        } catch (InvalidUsageException e) {
                            err = "read: invalid usage error on scanner read: " + e.getMessage();
                        } catch (OperationFailureException ex) {
                            err = "read: error setting up scanner read: " + ex.getResults().toString();
                        } catch (Exception exc) {
                            err = exc.getMessage();
                        }

                        if (err != null) {
                            Log.e("RFID", err);
                        }
                        break;
                    case WRITE:
                        if (!active) {
                            // TODO: Richtig Implementieren
//                            this.write(this.config);
                        }
                        break;
                }
            } else if (eventData == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
                this.cancel();
            }
        }

        // TODO: Richtig Implementieren
        if (event.hasKey("RFIDStatusEvent")) {
        //    if (!active) {
            this.dispatchEvent("RFIDStatusEvent", event);
        }
        //    } else if (rfidStatusEvents.StatusEventData.HandheldTriggerEventData.getHandheldEvent() == HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED) {
        //        deferTriggerReleased = true;
        //    }
//       }
    }
}